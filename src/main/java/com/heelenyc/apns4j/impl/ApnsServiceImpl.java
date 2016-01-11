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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.SocketFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.heelenyc.apns4j.IApnsConnection;
import com.heelenyc.apns4j.IApnsFeedbackConnection;
import com.heelenyc.apns4j.IApnsService;
import com.heelenyc.apns4j.impl.woker.SendWokerWithNotification;
import com.heelenyc.apns4j.impl.woker.SendWokerWithPlay;
import com.heelenyc.apns4j.model.ApnsConfig;
import com.heelenyc.apns4j.model.ApnsConstants;
import com.heelenyc.apns4j.model.Feedback;
import com.heelenyc.apns4j.model.Payload;
import com.heelenyc.apns4j.model.PushNotification;
import com.heelenyc.apns4j.tools.ApnsTools;

/**
 * The service should be created twice at most. One for the development env, and
 * the other for the production env
 * 
 */
public class ApnsServiceImpl implements IApnsService {
    private static Log logger = LogFactory.getLog(ApnsServiceImpl.class);

    // 主要资源：线程池和连接池
    private ThreadPoolExecutor executorService = null;
    private ApnsConnectionPool connPool = null;
    // 自检调度线程池
    private ScheduledExecutorService ses = Executors.newScheduledThreadPool(2, new ThreadFactory() {

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "ApnsServiceImpl-checkfailure");
        }
    });
    private BlockingQueue<Runnable> requestQueue = null;
    private AtomicLong reqs = new AtomicLong();
    private AtomicInteger threadId = new AtomicInteger(0);

    private ApnsConfig config;
    private SocketFactory factory;
    private long lastBuildTime = 0l; // 资源上次重建的时间

    private IApnsFeedbackConnection feedbackConn = null;

    private ApnsContext apnsContext;

    private static final int timeoutSec = 5;

    public static IApnsService createInstance(ApnsConfig config) {
        checkConfig(config);
        ApnsServiceImpl service = new ApnsServiceImpl(config);
        return service;
    }

    private static void checkConfig(ApnsConfig config) {
        if (config == null || config.getKeyStore() == null || config.getPassword() == null || "".equals(config.getPassword().trim())) {
            throw new IllegalArgumentException("KeyStore and password can't be null");
        }
        if (config.getPoolSize() <= 0 || config.getRetries() <= 0 || config.getCacheLength() <= 0) {
            throw new IllegalArgumentException("poolSize,retry, cacheLength must be positive");
        }
    }

    private ApnsServiceImpl(ApnsConfig config) {

        try {
            checkConfig(config);
            this.config = config;
            // 首先创建上下文
            apnsContext = ApnsContext.createApnsContext(config, this);

            int poolSize = config.getPoolSize();
            requestQueue = new LinkedBlockingQueue<Runnable>();
            executorService = new ThreadPoolExecutor(poolSize, poolSize, 10, TimeUnit.SECONDS, requestQueue, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "ApnsServiceImpl-woker-" + threadId.getAndIncrement());
                }
            });

            SocketFactory factory = ApnsTools.createSocketFactory(config.getKeyStore(), config.getPassword(), ApnsConstants.KEYSTORE_TYPE, ApnsConstants.ALGORITHM, ApnsConstants.PROTOCOL);
            this.factory = factory;
            connPool = ApnsConnectionPool.newConnPool(config, factory, getApnsContext());
            feedbackConn = new ApnsFeedbackConnectionImpl(config, factory);

            setLastBuildTime(System.currentTimeMillis());

            // 状态自检和恢复 应对网络故障
            ses.scheduleWithFixedDelay(new Runnable() {

                @Override
                public void run() {
                    checkFailure();
                }
            }, 60, 5, TimeUnit.SECONDS);
            // jvm停止前执行完所有send
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    logger.debug("ApnsServiceImp " + getName() + " stoped!");
                    shutdown();
                }
            });

            logger.info("finish construct ApnsServiceImpl!");
        } catch (Exception e) {
            logger.error("construct ApnsServiceImpl error : " + e.getMessage(), e);
        }
    }

    /**
     * 重建线程池 和连接
     */
    public void rebuild() {
        logger.error(String.format("Duang! rebuild threadpool and connpool ========, Stats : %s ", getStat()));

        // 保存之前的现场
        final ExecutorService preService = executorService;
        final ApnsConnectionPool preConnPool = connPool;

        // 这个地方有个问题，这个时候，很多连接被借出去了，一般是200个，最后还给新建的connpool了，所以先弃用
        if (preConnPool != null) {
            preConnPool.deprecate();
        }

        // 新建
        connPool = ApnsConnectionPool.newConnPool(config, factory, getApnsContext());
        executorService = new ThreadPoolExecutor(config.getPoolSize(), config.getPoolSize(), 10, TimeUnit.SECONDS, requestQueue, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "ApnsServiceImpl-woker-" + threadId.getAndIncrement());
            }
        });
        setLastBuildTime(System.currentTimeMillis());
        logger.error(String.format("finish rebuild threadpool and connpool "));

        // 另起线程回收之前的资源
        Thread thread = new Thread() {
            @Override
            public void run() {
                // 关闭线程池
                if (preService != null) {
                    preService.shutdown();
                    try {
                        preService.awaitTermination(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        logger.warn("Shutdown pre-ApnsService interrupted", e);
                    }
                }
                // 关闭连接池
                if (preConnPool != null) {
                    preConnPool.close();
                }
            };
        };
        thread.setName("rebuild-" + System.currentTimeMillis());
        thread.start();
    }

    /**
     * 自检
     */
    private void checkFailure() {
        boolean needRebuild = true;
        // 规则:
        // 1 系统至少运行了一分钟，避免在网络出现故障的时候，频繁重建
        // 2 连续三次（秒）检测，如果都发现有故障特征，立即重建

        if (getLastBuildTime() > System.currentTimeMillis() - 1000 * 60) {
            return;
        }

        int tryNum = 0;
        while (tryNum < 5) {
            tryNum++;
            // 判断故障症状, 若非故障状态，needRebuild = false 跳出
            if (!isFailure()) {
                needRebuild = false;
                break;
            } else {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
        if (needRebuild) {
            rebuild();
        }
    }

    /**
     * 判断是否故障状态
     * 
     * @return
     */
    private boolean isFailure() {
        if (getApnsContext().getSends() == 0 && getReqs() > 0) {
            return true;
        } else if (requestQueue.size() > 100 || getApnsContext().getResendQueueSize() > 200) {
            logger.warn("isFailure true : requestQueue.size() > 100 || getApnsContext().getResendQueueSize() > 200");
            return true;
        } else if (getActiveThreads() == config.getPoolSize()) {
            logger.warn("isFailure true : getActiveThreads() == config.getPoolSize()");
            return true;
        } else if (connPool.getUnavailableConnSize() >= connPool.getConnQueSize() * 0.8) {
            logger.warn("isFailure true : connPool.getUnavailableConnSize() >= connPool.getConnQueSize() * 0.8");
            return true;
        } else {
            return false;
        }
    }

    @Override
    public List<Feedback> getFeedbacks() {
        return feedbackConn.getFeedbacks();
    }

    @Override
    public void sendNotification(final PushNotification notification, boolean syncMode) {
        addReqCounts();
        if (syncMode) {
            IApnsConnection conn = null;
            try {
                conn = getConnection();
                conn.sendNotification(notification);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            } finally {
                if (conn != null) {
                    connPool.returnConn(conn);
                }
            }
        } else {
            SendWokerWithNotification worker = new SendWokerWithNotification(this, notification);
            Future<Boolean> task = executorService.submit(worker);
            try {
                if (task.get(timeoutSec, TimeUnit.SECONDS)) {
                    // do nothing
                }
            } catch (InterruptedException e) {
                // 不会被中断
                logger.error("submit send error " + e.getMessage(), e);
            } catch (ExecutionException e) {
                // 异常忽略
                logger.error("submit send error " + e.getMessage(), e);
            } catch (TimeoutException e) {
                // 超时要重传
                logger.error("submit send error  send timeout! ");
                // close 连接 no ! 不好直接关闭因为可能被blocked，但是又必须closesoket 因为可能发送慢
                worker.closeConn();
                // 中断任务
                task.cancel(true);
            }
        }
    }

    @Override
    public void sendNotification(final String token, final Payload payload, boolean syncMode) {
        addReqCounts();
        if (syncMode) {
            IApnsConnection conn = null;
            try {
                conn = getConnection();
                conn.sendNotification(token, payload);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            } finally {
                if (conn != null) {
                    connPool.returnConn(conn);
                }
            }
        } else {
            SendWokerWithPlay worker = new SendWokerWithPlay(this, token, payload);
            Future<Boolean> task = executorService.submit(worker);
            try {
                if (task.get(timeoutSec, TimeUnit.SECONDS)) {
                    // do nothing
                }
            } catch (InterruptedException e) {
                // 不会被中断
                logger.error("submit send error " + e.getMessage(), e);
            } catch (ExecutionException e) {
                // 异常忽略
                logger.error("submit send error " + e.getMessage(), e);
            } catch (TimeoutException e) {
                // 超时要重传
                logger.error("submit send error  send timeout! ");
                // 关闭连接 no!
                worker.closeConn();
                // 中断任务
                task.cancel(true);
            }
        }
    }

    @Override
    public void shutdown() {
        executorService.shutdown();
        try {
            executorService.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.warn("Shutdown ApnsService interrupted", e);
        }
        connPool.close();
    }

    public IApnsConnection getConnection() {
        IApnsConnection conn = connPool.borrowConn();
        if (conn == null) {
            throw new RuntimeException("Can't get apns connection");
        }
        return conn;
    }

    public int getWorkerQueuesize() {
        return requestQueue.size();
    }

    @Override
    public Map<String, String> getStat() {
        Map<String, String> stat = new HashMap<String, String>();
        stat.put(config.getName() + "-sendNum", String.valueOf(getApnsContext().getAndLClearSends()));
        stat.put(config.getName() + "-requestNum", String.valueOf(getAndClearReqs()));
        stat.put(config.getName() + "-queueSize", String.valueOf(getWorkerQueuesize()));
        stat.put(config.getName() + "-resendqueueSize", String.valueOf(getApnsContext().getResendQueueSize()));
        stat.put(config.getName() + "-activeThreads", String.valueOf(getActiveThreads()));
        stat.put(config.getName() + "-connqueueSize", String.valueOf(connPool.getConnQueSize()));
        stat.put(config.getName() + "-unavailableConnSize", String.valueOf(connPool.getUnavailableConnSize()));
        stat.put(config.getName() + "-slowSends", String.valueOf(getApnsContext().getAndLClearSlowSends()));
        stat.put(config.getName() + "-ignore4et", String.valueOf(getApnsContext().getAndClearIgnore4etMonitorAtomicLong()));
        stat.put(config.getName() + "-ets", String.valueOf(getApnsContext().getErrorTokenMapSize()));
        stat.put(config.getName() + "-deprecateds", String.valueOf(getApnsContext().getAndClearDeprecateds()));
        
        stat.put(config.getName() + "-<=10ms", String.valueOf(getApnsContext().getAndClearSlowMonitorAtomicLong10()));
        stat.put(config.getName() + "-<=50ms", String.valueOf(getApnsContext().getAndClearSlowMonitorAtomicLong50()));
        stat.put(config.getName() + "-<=1s", String.valueOf(getApnsContext().getAndClearSlowMonitorAtomicLong1000()));
        stat.put(config.getName() + "->1s", String.valueOf(getApnsContext().getAndClearSlowMonitorAtomicLong1000up()));
        
        return stat;
    }

    private int getActiveThreads() {
        if (executorService != null) {
            return executorService.getActiveCount();
        } else {
            return 0;
        }
    }

    /**
     * @return
     */
    public long getAndClearReqs() {
        return reqs.getAndSet(0);
    }

    public long getReqs() {
        return reqs.get();
    }

    public void addReqCounts() {
        reqs.getAndIncrement();
    }

    public long getLastBuildTime() {
        return lastBuildTime;
    }

    public void setLastBuildTime(long lastBuildTime) {
        this.lastBuildTime = lastBuildTime;
    }

    /**
     * @param conn
     */
    public void returnConn(IApnsConnection conn) {
        connPool.returnConn(conn);
    }

    public ApnsContext getApnsContext() {
        return apnsContext;
    }

    @Override
    public String getServiceName() {
        if (config != null) {
            return config.getName();
        }
        return "";
    }
    
}
