package com.heelenyc.apns4j.impl.woker;

import java.util.concurrent.Callable;

import com.heelenyc.apns4j.IApnsConnection;
import com.heelenyc.apns4j.impl.ApnsServiceImpl;
import com.heelenyc.apns4j.model.Payload;

/**
 * @author yicheng
 * @since 2015年3月20日
 * 
 */
public class SendWokerWithPlay implements Callable<Boolean> {

    // private Log logger = LogFactory.getLog(this.getClass());

    private ApnsServiceImpl service;
    private String token;
    private Payload payload;
    private volatile IApnsConnection conn = null;

    public SendWokerWithPlay(ApnsServiceImpl service, String token, Payload payload) {
        this.service = service;
        this.token = token;
        this.payload = payload;
    }

    @Override
    public Boolean call() throws Exception {
        IApnsConnection workconn = null;
        try {
            workconn = service.getConnection();
            this.conn = workconn;
            workconn.sendNotification(token, payload);
        } catch (Exception e) {
            workconn.setUnavailable();
            workconn.handleSendError();
            throw e;
        } finally {
            if (workconn != null) {
                service.returnConn(workconn);
            }
            this.conn = null;
        }
        return true;
    }

    /**
     * 
     */
    public void setConnUnavailable() {
        if (conn != null) {
            conn.setUnavailable();
        }
    }
    
    public void closeConn() {
        Thread thread = new Thread(){
            @Override
            public void run() {
                if (conn != null) {
                    conn.closeSocket();
                }
            };
        };
        thread.setName("SendWokerWithPlay-closeConn-" + System.currentTimeMillis());
        thread.start();
    }

}
