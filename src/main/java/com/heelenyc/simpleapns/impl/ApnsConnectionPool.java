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

import static com.heelenyc.simpleapns.model.ApnsConstants.HOST_DEVELOPMENT_ENV;
import static com.heelenyc.simpleapns.model.ApnsConstants.HOST_PRODUCTION_ENV;
import static com.heelenyc.simpleapns.model.ApnsConstants.PORT_DEVELOPMENT_ENV;
import static com.heelenyc.simpleapns.model.ApnsConstants.PORT_PRODUCTION_ENV;

import java.io.Closeable;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.net.SocketFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.heelenyc.simpleapns.api.IApnsConnection;
import com.heelenyc.simpleapns.model.ApnsConfig;

/**
 * 
 * @author RamosLi
 * 
 */
public class ApnsConnectionPool implements Closeable {
    private Log logger = LogFactory.getLog(ApnsConnectionPool.class);

    private static int CONN_ID_SEQ = 1;

    private SocketFactory factory;
    private BlockingQueue<IApnsConnection> connQueue = null;

    private ApnsConnectionPool(ApnsConfig config, SocketFactory factory) {
        this.factory = factory;

        String host = HOST_PRODUCTION_ENV;
        int port = PORT_PRODUCTION_ENV;
        if (config.isDevEnv()) {
            host = HOST_DEVELOPMENT_ENV;
            port = PORT_DEVELOPMENT_ENV;
        }

        int poolSize = config.getPoolSize();
        connQueue = new LinkedBlockingQueue<IApnsConnection>(poolSize);
        
        for (int i = 0; i < poolSize; i++) {
            String connName = (config.isDevEnv() ? "dev-" : "pro-") + CONN_ID_SEQ++;
            IApnsConnection conn = new ApnsConnectionImpl(this.factory, host, port, config.getRetries(), config.getCacheLength(), config.getName(), connName, config.getIntervalTime(),
                    config.getTimeout());
            connQueue.add(conn);
        }
    }

    /**
     * 那到的连接可能正在处理重发，跳过这样的连接，继续take
     * @return
     */
    public IApnsConnection borrowConn() {
        try {
            while (true) {
                IApnsConnection conn = connQueue.take();
                if (!conn.isAvailable()) {
                    // conn 正在重发或者重连，不可用
                    logger.error(String.format("borrow a unavailable conn %s , skip it!",((ApnsConnectionImpl)conn).getConnName()));
                    connQueue.add(conn);
                } else {
                    return conn;
                }
            }
            //return connQueue.take();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public void returnConn(IApnsConnection conn) {
        if (conn != null) {
            connQueue.add(conn);
        }
    }

    @Override
    public void close() {
        while (!connQueue.isEmpty()) {
            try {
                connQueue.take().close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * create instance
     * 
     * @param config
     * @return
     */
    public static ApnsConnectionPool newConnPool(ApnsConfig config, SocketFactory factory) {
        return new ApnsConnectionPool(config, factory);
    }
}
