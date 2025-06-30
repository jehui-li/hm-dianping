package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private final static UUID uuid = UUID.randomUUID();

    private StringRedisTemplate stringRedisTemplate;

    private final static String lockKeyPrefix = "lock:";

    private String lockKey;

    private static final DefaultRedisScript<Long> REDISSCRIPT = new DefaultRedisScript();
    static {
        REDISSCRIPT.setResultType(Long.class);
        REDISSCRIPT.setLocation(new ClassPathResource("unlock"));
    }

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String lockKey) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.lockKey = lockKey;
    }

    @Override
    public boolean tryLock(long timeout, TimeUnit unit) {
        Boolean res = stringRedisTemplate.opsForValue().setIfAbsent(lockKeyPrefix + lockKey, uuid.toString() + Thread.currentThread(), timeout, unit);
        return BooleanUtil.isTrue(res);
    }

//    public void unlock2() {
//        stringRedisTemplate.delete(lockKeyPrefix + lockKey);
//    }

    @Override
    public void unlock() {
        stringRedisTemplate.execute(
                REDISSCRIPT,
                Collections.singletonList(lockKeyPrefix + lockKey),
                uuid.toString() + Thread.currentThread()
        );
    }
    /**
     * 判断库存是否大于0（key：库存前缀+商品id）
     * 否，返回
     * 判断是否下过订单（key：订单前缀+商品id）
     * 是，返回
     * 扣减库存，将用户id加入商品订单的集合（在redis中
     */
    /**
     * key：库存前缀+商品id，value：库存数量
     * key：订单前缀+商品id，value：一个set，存用户id
     */
}
