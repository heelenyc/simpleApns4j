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
package com.heelenyc.simpleapns.impl;



import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.SocketFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.heelenyc.simpleapns.api.IApnsConnection;
import com.heelenyc.simpleapns.api.IApnsFeedbackConnection;
import com.heelenyc.simpleapns.api.IApnsService;
import com.heelenyc.simpleapns.model.ApnsConfig;
import com.heelenyc.simpleapns.model.Feedback;
import com.heelenyc.simpleapns.model.Payload;
import com.heelenyc.simpleapns.model.PushNotification;
import com.heelenyc.simpleapns.utils.ApnsTools;





/**
 * The service should be created twice at most. One for the development env, and
 * the other for the production env
 * 
 */
public class ApnsServiceImpl implements IApnsService {
    private static Log logger = LogFactory.getLog(ApnsServiceImpl.class);
    private static Map<String, IApnsService> serviceCacheMap = new HashMap<String, IApnsService>(3);

    private ExecutorService service = null;
    private BlockingQueue<Runnable> requestQueue = null;
    private AtomicLong sends= new AtomicLong();
    private AtomicInteger threadId = new AtomicInteger(0);
    
    private ApnsConnectionPool connPool = null;

    private IApnsFeedbackConnection feedbackConn = null;

    public static IApnsService createInstance(ApnsConfig config) {
        checkConfig(config);
        String name = config.getName();
        IApnsService service = getCachedService(name);
        if (service == null) {
            synchronized (name.intern()) {
                service = getCachedService(name);
                if (service == null) {
                    service = new ApnsServiceImpl(config);
                    serviceCacheMap.put(name, service);
                }
            }
        }
        return service;
    }

    public static IApnsService getCachedService(String name) {
        return serviceCacheMap.get(name);
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
        int poolSize = config.getPoolSize();
        requestQueue = new LinkedBlockingQueue<Runnable>();
        //service = Executors.newFixedThreadPool(poolSize);
        service = new ThreadPoolExecutor(poolSize, poolSize, 10, TimeUnit.SECONDS, requestQueue, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "ApnsServiceImpl-woker-" + threadId.getAndIncrement());
            }
        });

        SocketFactory factory = ApnsTools.createSocketFactory(config.getKeyStore(), config.getPassword(), KEYSTORE_TYPE, ALGORITHM, PROTOCOL);
        connPool = ApnsConnectionPool.newConnPool(config, factory);
        feedbackConn = new ApnsFeedbackConnectionImpl(config, factory);

        // 创建上下文
        ApnsContext.createApnsContext(config,this);

        // jvm停止前执行完所有send
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                logger.debug("ApnsServiceImp " + getName() + " stoped!");
                shutdown();
            }
        });
    }

    @Override
    public List<Feedback> getFeedbacks() {
        return feedbackConn.getFeedbacks();
    }

    @Override
    public void sendNotification(final PushNotification notification) {
        service.execute(new Runnable() {
            @Override
            public void run() {
                IApnsConnection conn = null;
                try {
                    conn = getConnection();
                    conn.sendNotification(notification);
                    addSendCounts();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                } finally {
                    if (conn != null) {
                        connPool.returnConn(conn);
                    }
                }
            }
        });
    }

    @Override
    public void sendNotification(final String token, final Payload payload) {
        service.execute(new Runnable() {
            @Override
            public void run() {
                IApnsConnection conn = null;
                try {
                    conn = getConnection();
                    conn.sendNotification(token, payload);
                    addSendCounts();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                } finally {
                    if (conn != null) {
                        connPool.returnConn(conn);
                    }
                }
            }
        });
    }

    @Override
    public void shutdown() {
        service.shutdown();
        try {
            service.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.warn("Shutdown ApnsService interrupted", e);
        }
        connPool.close();
    }

    private IApnsConnection getConnection() {
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
        for (Entry<String, IApnsService> serviceEntry : serviceCacheMap.entrySet()) {
            //logger.info(String.format("service- sends : %s  queue size : %s", serviceEntry.getKey(),getAndClearSends(),getWorkerQueuesize()));
            stat.put(serviceEntry.getKey()+"-sendNum", String.valueOf(ApnsContext.getAndLClearSends()));
            stat.put(serviceEntry.getKey()+"-requestNum", String.valueOf(getAndClearSends()));
            stat.put(serviceEntry.getKey()+"-queueSize", String.valueOf(getWorkerQueuesize()));
            stat.put(serviceEntry.getKey()+"-connqueueSize", String.valueOf(connPool.getConnQueSize()));
        }
        return stat;
    }
    
    /**
     * @return
     */
    public long getAndClearSends() {
        return sends.getAndSet(0);
    }

    public void addSendCounts(){
        sends.getAndIncrement();
    }
}
