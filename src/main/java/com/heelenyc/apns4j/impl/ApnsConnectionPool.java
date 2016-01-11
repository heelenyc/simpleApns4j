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

import java.io.Closeable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.net.SocketFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.heelenyc.apns4j.IApnsConnection;
import com.heelenyc.apns4j.model.ApnsConfig;
import com.heelenyc.apns4j.model.ApnsConstants;

public class ApnsConnectionPool implements Closeable {
    private Log logger = LogFactory.getLog(ApnsConnectionPool.class);

    private int CONN_ID_SEQ = 1;

    private SocketFactory factory;
    private BlockingQueue<IApnsConnection> connQueue = null;
    private Set<IApnsConnection> connSet = null;

    private ApnsConnectionPool(ApnsConfig config, SocketFactory factory, ApnsContext apnsContext) {
        this.factory = factory;

        String host = ApnsConstants.HOST_PRODUCTION_ENV;
        int port = ApnsConstants.PORT_PRODUCTION_ENV;
        if (config.isDevEnv()) {
            host = ApnsConstants.HOST_DEVELOPMENT_ENV;
            port = ApnsConstants.PORT_DEVELOPMENT_ENV;
        }

        int poolSize = config.getPoolSize();
        // 连接数比线程数多一点，应对偶尔（不可用）的连接，比如正在重建socket。
        int connSize = poolSize;
        if (!config.isDevEnv()) {
            connSize += connSize * 0.25;
        }
        connQueue = new LinkedBlockingQueue<IApnsConnection>();
        // 保留所有的 conn
        connSet = Collections.synchronizedSet(new HashSet<IApnsConnection>());

        for (int i = 0; i < connSize; i++) {
            String connName = (config.isDevEnv() ? "dev-conn-" : "pro-conn-") + CONN_ID_SEQ++;
            IApnsConnection conn = new ApnsConnectionImpl(this.factory, host, port, config.getRetries(), config.getCacheLength(), config.getName(), connName, config.getIntervalTime(),
                    config.getTimeout(), apnsContext);
            connQueue.add(conn);
            connSet.add(conn);
        }
    }

    public int getConnQueSize() {
        return connQueue.size();
    }

    public int getUnavailableConnSize() {
        int size = 0;
        for (IApnsConnection conn : connQueue) {
            if (!conn.isAvailable()) {
                size++;
            }
        }
        return size;
    }

    /**
     * 那到的连接可能正在处理重发，跳过这样的连接，继续take
     * 
     * @return
     */
    public IApnsConnection borrowConn() {
        try {
            while (true) {
                IApnsConnection conn = connQueue.take();
                if (!conn.isAvailable()) {
                    // conn 正在重发或者重连，不可用
                    // logger.error(String.format(Thread.currentThread().getName() + ": borrow a unavailable conn %s , skip it!", ((ApnsConnectionImpl) conn).getConnName()));
                    connQueue.add(conn);
                } else {
                    return conn;
                }
            }
            // return connQueue.take();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public void returnConn(IApnsConnection conn) {
        if (conn != null && !conn.isDeprecated()) {
            connQueue.add(conn);
        }
    }

    @Override
    public void close() {
        // close all connection
        // while (!connQueue.isEmpty()) {
        // try {
        // connQueue.take().close();
        // } catch (Exception e) {
        // e.printStackTrace();
        // }
        // }
        for (IApnsConnection conn : connSet) {
            try {
                conn.close();
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    /**
     * 弃用这个连接池
     */
    public void deprecate() {
        for (IApnsConnection conn : connSet) {
            try {
                conn.setDeprecated(true);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    /**
     * create instance
     * 
     * @param config
     * @return
     */
    public static ApnsConnectionPool newConnPool(ApnsConfig config, SocketFactory factory, ApnsContext apnsContext) {
        return new ApnsConnectionPool(config, factory, apnsContext);
    }
}
