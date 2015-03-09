package com.heelenyc.simpleapns.impl;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.heelenyc.simpleapns.api.IApnsService;
import com.heelenyc.simpleapns.model.ApnsConfig;
import com.heelenyc.simpleapns.model.PushNotification;
import com.heelenyc.simpleapns.utils.LRUMapCache;

/**
 * @author yicheng
 * @since 2015年2月28日
 * 
 */
public class ApnsContext {

    private Log logger = LogFactory.getLog(ApnsContext.class);
    
    private static ApnsContext instance = null;

    private LRUMapCache<String, Boolean> errorTokenCache = null;
    private ApnsConfig config = null;
    
    // 全局重发队列
    private static Queue<PushNotification> resendQueue = new ConcurrentLinkedQueue<PushNotification>();
    private ScheduledExecutorService ex = Executors.newScheduledThreadPool(2);
    
    private static AtomicLong sendCountMonitorAtomicLong = new AtomicLong();
    
    private IApnsService apnsService;
    
    // 统计该是

    public static void createApnsContext(ApnsConfig config, IApnsService apnsService) {
        instance = new ApnsContext(config,apnsService);
    }

    public static ApnsContext getInstance() {
        if (instance == null) {
            throw new RuntimeException("null ApnsContext instance , please create it first!");
        }
        return instance;
    }

    /**
     * @param apnsService 
     * 
     */
    public ApnsContext(final ApnsConfig config, IApnsService apnsService) {
        this.config = config;
        this.apnsService = apnsService;
        if (config.isCacheErrorToken()) {
             errorTokenCache = new LRUMapCache<String,Boolean>(config.getCacheErrotTokenExpires());
        }
        // resend
        ex.scheduleWithFixedDelay(new Runnable() {
            
            @Override
            public void run() {
                if (!resendQueue.isEmpty()) {
                    IApnsService service = ApnsServiceImpl.getCachedService(config.getName());
                    int size =  resendQueue.size();
                    for(int i = 0; i< size ; i++){
                        service.sendNotification(resendQueue.poll());
                    }
                    logger.info("resend " + size + " notices in ApnsContext, queue size : " + resendQueue.size());
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
        
        // count
        ex.scheduleWithFixedDelay(new Runnable() {
            
            @Override
            public void run() {
                logger.info(sendCountMonitorAtomicLong + " sends in last period (ApnsContext) !");
                sendCountMonitorAtomicLong = new AtomicLong(0);
                getApnsService().stat();
            }
        }, 1, 1, TimeUnit.MINUTES);
    }
    
    public static void addSendCounts(){
        sendCountMonitorAtomicLong.getAndIncrement();
    }
    
    /**
     * 全局重发
     */
    public static void addForResend(PushNotification pn){
        if (pn != null) {
            resendQueue.add(pn);
        }
    }
    

    public LRUMapCache<String, Boolean> getErrorTokenCache() {
        return errorTokenCache;
    }

    public static boolean isErrorToken(String token) {
        if (getInstance().getConfig().isCacheErrorToken()) {
            // 打开了开关才判断，否则都false并推送
            Boolean value = getInstance().getErrorTokenCache().get(token);
            if (value == null || value.equals(Boolean.FALSE)) {
                return false;
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    public static void cacheErrorToken(String token) {
        if (getInstance().getConfig().isCacheErrorToken()) {
            // 打开了开关才缓存，否则do nothing
            getInstance().getErrorTokenCache().put(token, Boolean.TRUE);
        }
    }

    public ApnsConfig getConfig() {
        return config;
    }

    public IApnsService getApnsService() {
        return apnsService;
    }
}
