package com.heelenyc.apns4j.impl.woker;

import java.util.concurrent.Callable;

import com.heelenyc.apns4j.IApnsConnection;
import com.heelenyc.apns4j.impl.ApnsServiceImpl;
import com.heelenyc.apns4j.model.PushNotification;

/**
 * @author yicheng
 * @since 2015年3月20日
 * 
 */
public class SendWokerWithNotification implements Callable<Boolean> {

    // private Log logger = LogFactory.getLog(this.getClass());

    private ApnsServiceImpl service;
    private PushNotification notification;

    private volatile IApnsConnection conn = null;

    public SendWokerWithNotification(ApnsServiceImpl service, PushNotification notification) {
        this.service = service;
        this.notification = notification;
    }

    @Override
    public Boolean call() throws Exception {
        IApnsConnection workconn = null;
        try {
            workconn = service.getConnection();
            this.conn = workconn;
            workconn.sendNotification(notification);
            // service.addSendCounts();
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
        thread.setName("SendWokerWithNotification-closeConn-" + System.currentTimeMillis());
        thread.start();
    }

}
