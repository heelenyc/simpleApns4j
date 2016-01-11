/*
 * Copyright 2013 DiscoveryBay Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.heelenyc.apns4j.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.SocketFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.heelenyc.apns4j.IApnsConnection;
import com.heelenyc.apns4j.model.Command;
import com.heelenyc.apns4j.model.ErrorResponse;
import com.heelenyc.apns4j.model.Payload;
import com.heelenyc.apns4j.model.PushNotification;
import com.heelenyc.apns4j.tools.ApnsTools;

import com.heelenyc.apns4j.model.ApnsConstants;

public class ApnsConnectionImpl implements IApnsConnection {

    private static AtomicInteger IDENTIFIER = new AtomicInteger(100);

    private Log logger = LogFactory.getLog(ApnsConnectionImpl.class);

    // never expire
    private int EXPIRE = Integer.MAX_VALUE;

    private SocketFactory factory;
    /**
     * EN: There are two threads using the socket, one for writing, one for
     * reading. CN: 一个socket，最多有两个线程在使用它，一个读一个写。
     */
    private volatile Socket socket;
    private Object socketLock = new Object();
    /**
     * When a notification is sent, cache it into this queue. It may be resent.
     */
    private Queue<PushNotification> notificationCachedQueue = new ConcurrentLinkedQueue<PushNotification>();

    /**
     * Whether find error in this connection
     */
    private volatile boolean errorHappended = false;

    /**
     * Whether first write data in the connection
     */
    private volatile boolean isFirstWrite = false;

    // conn 是否弃用
    private volatile boolean deprecated = false;

    private int maxRetries;
    private int maxCacheLength;

    private int readTimeOut;

    private String host;
    private int port;

    /**
     * You can find the properly ApnsService to resend notifications by this
     * name
     */
    private String serviceName;

    /**
     * connection name
     */
    private String connName;
    private int intervalTime;
    private long lastSuccessfulTime = 0;

    private AtomicInteger notificaionSentCount = new AtomicInteger(0);
    private Object lock = new Object();
    private ScheduledExecutorService es = Executors.newScheduledThreadPool(2);

    private int slowSendTimes = 0;
    private long lastSlowSendTimestamp = 0;

    private ApnsContext apnsContext;

    public ApnsConnectionImpl(SocketFactory factory, String host, int port, int maxRetries, int maxCacheLength, String name, String connName, int intervalTime, int timeout, ApnsContext apnsContext) {
        this.factory = factory;
        this.host = host;
        this.port = port;
        this.maxRetries = maxRetries;
        this.maxCacheLength = maxCacheLength;
        this.serviceName = name;
        this.connName = connName;
        this.intervalTime = intervalTime;
        this.readTimeOut = timeout;
        this.apnsContext = apnsContext;

        // 循环 检测socket是否可用
        es.scheduleWithFixedDelay(new Runnable() {

            @Override
            public void run() {
                if (socket == null || errorHappended ) { //|| socket.isClosed()
                    try {
                        createNewSocket();
                        // test nofication
                        // testHeartBeat();
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }
        }, 500, 500, TimeUnit.MILLISECONDS);
    }

    /**
     * 
     */
    protected void testHeartBeat() {
        String token = "0b3efeb8aeb8d0442a2008b8a2b5f08ff32a98bc4cb672e6b972fd93d500b6cd";
        Payload payload = new Payload();
        payload.setAlert("How are you?");
        payload.setBadge(1);
        payload.setSound("msg.mp3");
        payload.addParam("uid", 123456);
        payload.addParam("type", 12);
        sendNotification(token, payload);
    }

    @Override
    public void sendNotification(String token, Payload payload) {
        PushNotification notification = new PushNotification();
        notification.setExpire(EXPIRE);
        notification.setToken(token);
        notification.setPayload(payload);
        sendNotification(notification);
    }

    @Override
    public void sendNotification(PushNotification notification) {

        if (notification != null && getApnsContext().isErrorToken(notification.getToken())) {
            logger.info("error token : " + notification.getToken() + " in sendNotification, ignore : " + notification.getPayload().getAlertBody());
            this.getApnsContext().addIgnore4etMonitorAtomicLong();
            return;
        }
        if (notification.isDeprecated()) {
            logger.info("expired notification , lifetime: " + notification.getLifeTime() + " ms , ignore :" + notification.getPayload().getAlertBody());
            apnsContext.addDeprecateCounts();
            return;
        }
        long startTimestamp = System.currentTimeMillis();

        // 可能是重传的进来了，所以只能在这个地方设置id，因为上个id可能不是这个连接设置的
        notification.setId(IDENTIFIER.incrementAndGet());
        byte[] plBytes = null;
        String payload = notification.getPayload().toString();
        try {
            plBytes = payload.getBytes(ApnsConstants.CHARSET_ENCODING);
            if (plBytes.length > ApnsConstants.PAY_LOAD_MAX_LENGTH) {
                logger.error("Payload execeed limit, the maximum size allowed is 256 bytes. " + payload);
                return;
            }
        } catch (UnsupportedEncodingException e) {
            logger.error(e.getMessage(), e);
            return;
        }

        /**
         * EN: If error happened before, just wait until the resending work
         * finishes by another thread and close the current socket CN:
         * 如果发现当前连接有error-response，加锁等待，直到另外一个线程把重发做完后再继续发送
         */
        synchronized (lock) {
            if (errorHappended) {
                closeSocket();
            }
            byte[] data = notification.generateData(plBytes);
            boolean isSuccessful = false;
            int retries = 0;
            try {
                if (this.getApnsContext().getConfig().isDevEnv()) {
                    retries = -2; // 如果是开发版本，多重试两次。
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
            
            while (retries <= maxRetries) {
                try {
                    // 考虑超过idle期限，苹果会单方面关闭连接
                    boolean exceedIntervalTime = lastSuccessfulTime > 0 && (System.currentTimeMillis() - lastSuccessfulTime) > intervalTime;
                    if (exceedIntervalTime) {
                        throw new Exception("socket expired cause by idle;  last lastSuccessfulTime : " + new Date(lastSuccessfulTime));
                    }
                    // 检查连接质量
                    checkConn();
                    // socket不可用，直接出去，等重发,
                    // 不行！，当请求量相对连接数少一个数量级的时候，有问题，一开始handshake异常，然后重试次数用完了，socket都没建好，然后转到其他的连接重发也是如此
                    // 还是得循环
                    while (!this.isAvailable()) {
                        Thread.sleep(200);
                    }

                    OutputStream socketOs = socket.getOutputStream();
                    // 注意 另外的线程在阻塞读操作
                    socketOs.write(data);
                    socketOs.flush();

                    isSuccessful = true;
                    break;
                } catch (Exception e) {
                    logger.error(connName + " " + e.getMessage(), e);
                    closeSocket();
                    socket = null;
                }
                retries++;
            }
            if (!isSuccessful) {
                logger.error(String.format("%s Notification send failed. %s", connName, notification));
                getApnsContext().addForResend(notification);
                return;
            } else {
                // 慢日志
                long end = System.currentTimeMillis();
                getApnsContext().globalSlowInfo(end - startTimestamp,socket.getInetAddress().getHostAddress());
                if (getApnsContext().addSlowSendCounts(end - notification.getBornTimestamp())) {
                    logger.info(String.format("%d ms ! slow send in %s retry : %d , sendcost %s ms", end - notification.getBornTimestamp(), getConnName(), retries, end - startTimestamp));
                    recordSlowInfo(end);
                }
                notificaionSentCount.incrementAndGet();
                getApnsContext().addSendCounts();
                // logger.info(String.format("%s Send success. count: %s, notificaion: %s",
                // connName, notificaionSentCount.incrementAndGet(),
                // notification));

                notificationCachedQueue.add(notification);
                lastSuccessfulTime = System.currentTimeMillis();

                /**
                 * TODO there is a bug, maybe, theoretically. CN:
                 * 假如我们发了一条错误的通知，然后又发了 maxCacheLength 条正确的通知。这时APNS服务器
                 * 才返回第一条通知的error-response。此时，第一条通知已经从队列移除了。。
                 * 其实后面100条该重发，但却没有。不过这个问题的概率很低，我们还是信任APNS服务器能及时返回
                 */
                if (notificationCachedQueue.size() > maxCacheLength) {
                    notificationCachedQueue.poll();
                }
            }
        }

        if (isFirstWrite) {
            isFirstWrite = false;
            startErrorWorker();
        }
    }

    private void createNewSocket() throws IOException, UnknownHostException {

        Socket socket_new = factory.createSocket(host, port);
        socket_new.setSoTimeout(readTimeOut);
        socket_new.setTcpNoDelay(true);
        // 这个设置很重要，不然很多情况下会卡线程，主要close的行为
        socket_new.setSoLinger(true, 0);

        if (socket_new.isConnected()) {
            synchronized (socketLock) {
                isFirstWrite = true;
                errorHappended = false;
                slowSendTimes = 0;
                lastSlowSendTimestamp = 0;
                lastSuccessfulTime = 0;
                socket = socket_new;
            }
            logger.info(connName + " finish create a new socket.");
        } else {
            logger.info(connName + " finish create a new socket.bu is not connected");
        }
//        try {
//            this.getApnsContext().registTotalIps(socket.getInetAddress().getHostAddress());
//        } catch (Exception e) {
//            logger.error("regisTotalIps error ", e);
//        }
        
    }

    public void closeSocket() {
        if (socket != null) {
//            try {
//                this.getApnsContext().unRegistTotalIps(socket.getInetAddress().getHostAddress());
//            } catch (Exception e) {
//                logger.error("unregisTotalIps error ", e);
//            }
            
            synchronized (socketLock) {
                try {
                    if (socket != null) {
                        socket.close();
                    }
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                } finally {
                    socket = null;
                }
            }
        }
    }

    private boolean isSocketAlive(Socket socket) {
        if (socket != null && socket.isConnected()) {
            return true;
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        es.shutdown();
        closeSocket();
        setDeprecated(true);
    }

    private void startErrorWorker() {
        Thread thread = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    if (!isSocketAlive(socket)) {
                        return;
                    }
                    InputStream socketIs = socket.getInputStream();
                    byte[] res = new byte[ApnsConstants.ERROR_RESPONSE_BYTES_LENGTH];
                    int size = 0;

                    while (true) {
                        try {
                            // 注意这是阻塞操作，主要依赖于超时设置
                            size = socketIs.read(res);
                            if (size > 0 || size == -1) {
                                // break, when something was read or there is no
                                // data any more
                                break;
                            }
                        } catch (SocketTimeoutException e) {
                            // There is no data. Keep reading.
                            // 这里出现这个是正常的，因为一般情况是没有数据读取的
                            // logger.error(connName + " " + e.getMessage() +
                            // " Keep reading", e);
                        }
                    }

                    int command = res[0];
                    /**
                     * EN: error-response,close the socket and resent
                     * notifications CN: 一旦遇到错误返回就关闭连接，并且重新发送在它之后发送的通知
                     */
                    if (size == res.length && command == Command.ERROR) {
                        // 一旦有错误发生，立马标记这个连接出错，不让外面调用这个连接
                        errorHappended = true;
                        int status = res[1];
                        int errorId = ApnsTools.parse4ByteInt(res[2], res[3], res[4], res[5]);

                        Queue<PushNotification> resentQueue = new LinkedList<PushNotification>();
                        PushNotification foundedPn = null;
                        synchronized (lock) {
                            boolean found = false;
                            while (!notificationCachedQueue.isEmpty()) {
                                PushNotification pn = notificationCachedQueue.poll();
                                if (pn.getId() == errorId) {
                                    found = true;
                                    foundedPn = pn;
                                    getApnsContext().cacheErrorToken(foundedPn.getToken());
                                    logger.info(String.format("cache error token : %s", pn.getToken()));
                                } else {
                                    /**
                                     * https://developer.apple.com/library/ios/
                                     * documentation
                                     * /NetworkingInternet/Conceptual
                                     * /RemoteNotificationsPG
                                     * /Chapters/CommunicatingWIthAPS.html As
                                     * the document said, add the notifications
                                     * which need be resent to the queue. Igonre
                                     * the error one
                                     */
                                    if (found) {
                                        resentQueue.add(pn);
                                    }
                                }
                            }
                            if (!found) {
                                logger.warn(connName + " Didn't find error-notification in the queue. Maybe it's time to adjust cache length. id: " + errorId);
                            }
                        }
                        if (logger.isInfoEnabled()) {
                            if (foundedPn != null) {
                                logger.info(String.format("%s Received error response. status: %s, id: %s, error-desc: %s, resend size: %s, errorToken: %s", connName, status, errorId,
                                        ErrorResponse.desc(status), resentQueue.size(), foundedPn.getToken()));
                            } else {
                                logger.info(String.format("%s Received error response. status: %s, id: %s, error-desc: %s, resend size: %s", connName, status, errorId, ErrorResponse.desc(status),
                                        resentQueue.size()));
                            }
                        }
                        // resend notifications
                        if (!resentQueue.isEmpty()) {
                            // 一般至少延后1分钟了，没有必要重发了
                            // getApnsContext().addForResend(resentQueue);
                        }
                    } else {
                        // ignore and continue reading
                        logger.error(connName + " Unexpected command or size. command: " + command + " , size: " + size);
                    }
                } catch (Exception e) {
                    // logger.error(connName + " " + e.getMessage(), e);
                    logger.error(connName + " " + e.getMessage() + " when read in startErrorWorker");
                } finally {
                    /**
                     * EN: close the old socket although it may be closed once
                     * before. CN: 介个连接可能已经被关过一次了，再关一下也无妨，万无一失嘛
                     */
                    closeSocket();
                }
            }
        });
        thread.setName("errorcheck-" + getConnName());
        thread.start();
    }

    public AtomicInteger getNotificaionSentCount() {
        return notificaionSentCount;
    }

    /**
     * 不在进行重发的时候认为是可用的
     */
    @Override
    public boolean isAvailable() {
        if (errorHappended) {
            // logger.error(connName +
            // " has error happend ! return false in isAvailable");
            return false;
        }
        if (socket == null) {
            // logger.error(connName +
            // "'s socket is not ready! return false in isAvailable");
            return false;
        }
        return true;
        // return !isResending;
    }

    public String getConnName() {
        return connName;
    }

    public int getSlowSendTimes() {
        return slowSendTimes;
    }

    public void setSlowSendTimes(int slowSendTimes) {
        this.slowSendTimes = slowSendTimes;
    }

    public long getLastSlowSendTimestamp() {
        return lastSlowSendTimestamp;
    }

    public void setLastSlowSendTimestamp(long lastSlowSendTimestamp) {
        this.lastSlowSendTimestamp = lastSlowSendTimestamp;
    }

    /**
     * 检查连接质量
     */
    private void checkConn() {
        if (lastSlowSendTimestamp < System.currentTimeMillis() - 60 * 1000) {
            slowSendTimes = 0;
            lastSlowSendTimestamp = 0;
        }
        if (slowSendTimes > 5) {
            // closeSocket();
            logger.warn(getConnName() + " slowSendTimes(per minute) : " + slowSendTimes);
        }
    }

    /**
     * 如果慢查询发生再1分钟之内，进行记录，用于判断连接质量
     * 
     * @param end
     */
    private void recordSlowInfo(long end) {
        // 第一次慢发送 直接记录
        if (lastSlowSendTimestamp == 0) {
            slowSendTimes++;
            lastSlowSendTimestamp = end;
            return;
        }
        // 非第一次，并且在一分钟之内，记录，否则清零
        if (lastSlowSendTimestamp > System.currentTimeMillis() - 60 * 1000) {
            slowSendTimes++;
            lastSlowSendTimestamp = end;
            return;
        } else {
            slowSendTimes = 0;
            lastSlowSendTimestamp = 0;
        }
    }

    @Override
    public boolean setUnavailable() {
        errorHappended = true;
        return true;
    }

    @Override
    public boolean isDeprecated() {
        return deprecated;
    }

    @Override
    public void setDeprecated(boolean deprecated) {
        this.deprecated = deprecated;
    }

    public String getServiceName() {
        return serviceName;
    }

    public ApnsContext getApnsContext() {
        return apnsContext;
    }

    @Override
    public void handleSendError() {
        try {
            // this.getApnsContext().registErrorIps(socket.getInetAddress().getHostAddress());
        } catch (Exception e) {
            logger.error("registErrorIps error ", e);
        }
    }
}
