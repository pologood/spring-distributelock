package com.github.blackshadowwalker.spring.distributelock.redis;

import com.github.blackshadowwalker.spring.distributelock.Lock;
import com.github.blackshadowwalker.spring.distributelock.LockException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.data.redis.core.BoundValueOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * Created by ASUS on 2016/8/16.
 */
public class RedisLock implements Lock {
    private static Log log = LogFactory.getLog(RedisLock.class);

    private RedisTemplate redisTemplate;

    private final String name;
    private final String key;
    private final long timeout;//ms
    private final long expire;//ms
    private final String msg;
    private final boolean autoUnlock;

    private final String lockName;

    public RedisLock(String name, String key, long timeout, long expire, String msg, RedisTemplate redisTemplate, boolean autoUnlock) {
        this.name = name;
        this.key = key;
        this.timeout = timeout;
        this.expire = expire;
        this.lockName = this.name + ":" + key;
        this.redisTemplate = redisTemplate;
        this.msg = msg;
        this.autoUnlock = autoUnlock;
    }

    @Override
    public boolean autoUnlock() {
        return autoUnlock;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public String getKey() {
        return this.key;
    }

    @Override
    public long getTimeout() {
        return this.timeout;
    }

    @Override
    public long getKeyExpire() {
        return this.expire;
    }

    @Override
    public String getLockName() {
        return this.lockName;
    }

    @Override
    public synchronized boolean lock() throws LockException {
        this.locked = false;
        if (StringUtils.isEmpty(name)) {
            throw new LockException("lock name is null");
        }
        this.tryLock();
        //has get the lock
        if (locked) {
            log.debug(this + " Get Lock: " + this.lockName);
            return true;
        }
        if (!msg.isEmpty()) {
            throw new LockException(msg);
        }
        return false;
    }

    private volatile boolean cancel = false;

    private volatile boolean locked = false;
    long MAX_TIMEOUT = 1000 * 3600 * 2;

    private void tryLock() {
        BoundValueOperations operations = redisTemplate.boundValueOps(lockName);
        long st = System.currentTimeMillis();
        while (!locked) {
            if (cancel) {
                break;
            }
            long timestamp = System.currentTimeMillis();
            long expireTime = (expire < 1) ? Long.MAX_VALUE : (timestamp + this.expire);
            long oldValue = get(operations);
            if (oldValue > 0 && timestamp > oldValue) {
                redisTemplate.delete(lockName);
            }

            locked = operations.setIfAbsent(String.valueOf(expireTime));
            if (locked) {
                operations.expire(expire, TimeUnit.MILLISECONDS);
                log.info("locked " + operations.getKey());
            }
            try {
                Long ttl = operations.getExpire();
                if (ttl != null && ttl > 10000) {
                    Thread.sleep(500);
                } else if (timeout > 0) {
                    Thread.sleep(50);
                } else {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
            }
            if (timeout > 0 && timestamp - st > timeout) {
                break;
            }
            if (timestamp - st > MAX_TIMEOUT) {
                break;
            }
        }
    }

    @Override
    public void unlock() {
        if (locked) {
            redisTemplate.delete(lockName);
            locked = false;
            cancel = false;
        }
    }

    private long get(BoundValueOperations valueOperations) {
        String value = (String) valueOperations.get();
        if (StringUtils.isEmpty(value)) {
            return 0;
        }
        return Long.parseLong(value);
    }

}
