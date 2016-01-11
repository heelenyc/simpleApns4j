package com.heelenyc.apns4j.impl;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.heelenyc.apns4j.IApnsService;
import com.heelenyc.apns4j.model.ApnsConfig;
import com.heelenyc.apns4j.model.Payload;
import com.heelenyc.apns4j.model.PushNotification;
import com.heelenyc.apns4j.tools.LRUMapCache;

/**
 * @author yicheng
 * @since 2015年2月28日
 * 
 */
public class ApnsContext {

    private static Log logger = LogFactory.getLog(ApnsContext.class);

    //private JedisPool jedisPool = null;
    // never expire
    private static int EXPIRE = Integer.MAX_VALUE;
    // slow send threshold in millsecond in log
    private static final int slowSendThresholdInMs = 50;

    private LRUMapCache<String, Boolean> errorTokenCache = null;
    private ApnsConfig config = null;

    // 全局重发队列
    private Queue<PushNotification> resendQueue = new ConcurrentLinkedQueue<PushNotification>();
    private ScheduledExecutorService ex = Executors.newScheduledThreadPool(2);

    private AtomicLong sendCountMonitorAtomicLong = new AtomicLong();
    private AtomicLong slowSendMonitorAtomicLong = new AtomicLong();
    private AtomicLong deprecateMonitorAtomicLong = new AtomicLong();
    // ignore for er
    private AtomicLong ignore4etMonitorAtomicLong = new AtomicLong();

    private AtomicLong slowMonitorAtomicLong10 = new AtomicLong();
    private AtomicLong slowMonitorAtomicLong50 = new AtomicLong();
    private AtomicLong slowMonitorAtomicLong1000 = new AtomicLong();
    private AtomicLong slowMonitorAtomicLong1000up = new AtomicLong();

    private IApnsService apnsService;

    /**
     * @param apnsService
     * 
     */
    public ApnsContext(final ApnsConfig config, final IApnsService apnsService) {
        this.config = config;
        this.apnsService = apnsService;
        if (config.isCacheErrorToken()) {
            errorTokenCache = new LRUMapCache<String, Boolean>(config.getCacheErrorTokenExpires());
        }

//        try {
//            JedisPoolConfig jedisPoolConfig = new JedisPoolConfig();
//            jedisPoolConfig.setMaxActive(100);
//            jedisPoolConfig.setMaxIdle(10);
//            jedisPoolConfig.setMaxWait(1000);
//            jedisPoolConfig.setTestOnBorrow(true);
//            jedisPool = new JedisPool(jedisPoolConfig, ApnsConstants.MONITOR_REDIS_HOST, ApnsConstants.MONITOR_REDIS_PORT);
//        } catch (Exception e) {
//            logger.error(e.getMessage(), e);
//        }
        // resend
        ex.scheduleWithFixedDelay(new Runnable() {

            @Override
            public void run() {
                if (!resendQueue.isEmpty()) {
                    int size = resendQueue.size();
                    for (int i = 0; i < size; i++) {
                        apnsService.sendNotification(resendQueue.poll(), false);
                    }
                    logger.info("resend " + size + " notices in ApnsContext, queue size : " + resendQueue.size());
                }
            }
        }, 1, 1, TimeUnit.SECONDS);

        // count
        // ex.scheduleWithFixedDelay(new Runnable() {
        //
        // @Override
        // public void run() {
        // logger.info(sendCountMonitorAtomicLong +
        // " sends in last period (ApnsContext) !");
        // sendCountMonitorAtomicLong = new AtomicLong(0);
        // }
        // }, 1, 1, TimeUnit.MINUTES);
    }

    public void globalSlowInfo(long spanInMS, String remotedIP) {
        if (spanInMS <= 10) {
            slowMonitorAtomicLong10.incrementAndGet();
        } else if (spanInMS <= 50) {
            slowMonitorAtomicLong50.incrementAndGet();
        } else if (spanInMS <= 1000) {
            slowMonitorAtomicLong1000.incrementAndGet();
        } else {
            slowMonitorAtomicLong1000up.incrementAndGet();
            //registErrorIps(remotedIP);
        }
    }

    // 统计该是
    public static ApnsContext createApnsContext(ApnsConfig config, IApnsService apnsService) {
        return new ApnsContext(config, apnsService);
    }

//    public void registTotalIps(String remotedHostIp) {
//        Jedis jedis = null;
//        try {
//            if (jedisPool != null) {
//                jedis = jedisPool.getResource();
//                jedis.zadd(ApnsConstants.MONITOR_IP_TOTAL_KEY, System.currentTimeMillis(), remotedHostIp);
//            } else {
//                logger.error("null jedisDao in apnscontext!");
//            }
//        } catch (Exception e) {
//            logger.error(remotedHostIp + e.getMessage(), e);
//        } finally {
//            if (jedis != null) {
//                jedisPool.returnResource(jedis);
//            }
//        }
//    }
//
//    public void unRegistTotalIps(String remotedHostIp) {
//        Jedis jedis = null;
//        try {
//            if (jedisPool != null) {
//                jedis = jedisPool.getResource();
//                jedis.zrem(ApnsConstants.MONITOR_IP_TOTAL_KEY, remotedHostIp);
//            } else {
//                logger.error("null jedisDao in apnscontext!");
//            }
//        } catch (Exception e) {
//            logger.error(remotedHostIp + e.getMessage(), e);
//        } finally {
//            if (jedis != null) {
//                jedisPool.returnResource(jedis);
//            }
//        }
//    }
//
//    public void registErrorIps(String remotedHostIp) {
//        Jedis jedis = null;
//        try {
//            if (jedisPool != null) {
//                jedis = jedisPool.getResource();
//                jedis.zadd(ApnsConstants.MONITOR_IP_ERROR_KEY, System.currentTimeMillis(), remotedHostIp);
//            } else {
//                logger.error("null jedisDao in apnscontext!");
//            }
//        } catch (Exception e) {
//            logger.error(remotedHostIp + e.getMessage(), e);
//        } finally {
//            if (jedis != null) {
//                jedisPool.returnResource(jedis);
//            }
//        }
//    }
//
//    public void unRegistErrorIps(String remotedHostIp) {
//        Jedis jedis = null;
//        try {
//            if (jedisPool != null) {
//                jedis = jedisPool.getResource();
//                jedis.zrem(ApnsConstants.MONITOR_IP_ERROR_KEY, remotedHostIp);
//            } else {
//                logger.error("null jedisDao in apnscontext!");
//            }
//        } catch (Exception e) {
//            logger.error(remotedHostIp + e.getMessage(), e);
//        } finally {
//            if (jedis != null) {
//                jedisPool.returnResource(jedis);
//            }
//        }
//    }

    public int getResendQueueSize() {
        return resendQueue.size();
    }

    public void addSendCounts() {
        sendCountMonitorAtomicLong.getAndIncrement();
    }

    public long getAndLClearSends() {
        long res = sendCountMonitorAtomicLong.get();
        sendCountMonitorAtomicLong = new AtomicLong(0);
        return res;
    }

    public long getSends() {
        return sendCountMonitorAtomicLong.get();
    }

    public boolean addSlowSendCounts(long timespanInMs) {
        if (timespanInMs >= slowSendThresholdInMs) {
            slowSendMonitorAtomicLong.getAndIncrement();
            return true;
        } else {
            return false;
        }
    }

    public long getAndLClearSlowSends() {
        return slowSendMonitorAtomicLong.getAndSet(0);
    }

    /**
     * 全局重发
     */
    public void addForResend(PushNotification pn) {
        if (pn != null) {
            resendQueue.add(pn);
        }
    }

    public void addForResend(Queue<PushNotification> pnQueue) {
        if (pnQueue != null && pnQueue.size() > 0) {
            resendQueue.addAll(pnQueue);
        }
    }

    public void addForResend(String connName, Queue<PushNotification> queue) {
        if (queue != null && queue.size() > 0) {
            resendQueue.addAll(queue);
            logger.info("addForResend for queue , name:  " + connName);
        } else {
            logger.error("addForResend for queue error  , name: " + connName);
        }
    }

    /**
     * @param token
     * @param payload
     */
    public void addForResend(String token, Payload payload) {
        PushNotification notification = new PushNotification();
        notification.setExpire(EXPIRE);
        notification.setToken(token);
        notification.setPayload(payload);

        addForResend(notification);
    }

    public LRUMapCache<String, Boolean> getErrorTokenCache() {
        return errorTokenCache;
    }

    public boolean isErrorToken(String token) {
        if (getConfig().isCacheErrorToken()) {
            // 打开了开关才判断，否则都false并推送
            Boolean value = getErrorTokenCache().get(token);
            if (value == null || value.equals(Boolean.FALSE)) {
                return false;
            } else {
                return true;
            }
        } else {
            return false;
        }
    }

    public void cacheErrorToken(String token) {
        if (getConfig().isCacheErrorToken()) {
            // 打开了开关才缓存，否则do nothing
            getErrorTokenCache().put(token, Boolean.TRUE);
        }
    }

    public ApnsConfig getConfig() {
        return config;
    }

    public IApnsService getApnsService() {
        return apnsService;
    }

    /**
     * @return
     * 
     */
    public long addDeprecateCounts() {
        return deprecateMonitorAtomicLong.getAndIncrement();
    }

    public long getDeprecateds() {
        return deprecateMonitorAtomicLong.get();
    }

    public long getAndClearDeprecateds() {
        return deprecateMonitorAtomicLong.getAndSet(0);
    }

    public long addIgnore4etMonitorAtomicLong() {
        return ignore4etMonitorAtomicLong.getAndIncrement();
    }

    public long getIgnore4etMonitorAtomicLong() {
        return ignore4etMonitorAtomicLong.get();
    }

    public long getAndClearIgnore4etMonitorAtomicLong() {
        return ignore4etMonitorAtomicLong.getAndSet(0);
    }

    public long getAndClearSlowMonitorAtomicLong10() {
        return slowMonitorAtomicLong10.getAndSet(0);
    }

    public long getAndClearSlowMonitorAtomicLong50() {
        return slowMonitorAtomicLong50.getAndSet(0);
    }

    public long getAndClearSlowMonitorAtomicLong1000() {
        return slowMonitorAtomicLong1000.getAndSet(0);
    }

    public long getAndClearSlowMonitorAtomicLong1000up() {
        return slowMonitorAtomicLong1000up.getAndSet(0);
    }

    /**
     * @return
     */
    public int getErrorTokenMapSize() {
        if (getErrorTokenCache() != null) {
            return getErrorTokenCache().getMapSize();
        } else {
            return 0;
        }
    }
}
