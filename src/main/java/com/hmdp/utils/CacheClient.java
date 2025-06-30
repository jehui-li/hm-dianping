package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 增，过期时间
    public void set
        (String key, Object value, long timeout, TimeUnit timeUnit) {
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), timeout, timeUnit);
    }

    public <T> void set2
            (String key, T value, long timeout, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), timeout, timeUnit);
    }

    // 增，逻辑过期
    public  void setWithLogicalExpire(String key, Object value, long timeout) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeout));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 改，主动更新
    @Transactional
    public void update(String key, Object value) {

    }
    // 查，解决缓存穿透
    public <R, T> R queryWithPassThrough(String prefix, T id, Class<R> clazz, Function<T, R> dbFallBack, long timeout, TimeUnit timeUnit) {
        // 查缓存
        String jsonstring = stringRedisTemplate.opsForValue().get(id);
        // 查得到，返回结果
        if (StrUtil.isNotBlank(jsonstring)) {
            return JSONUtil.toBean(jsonstring, clazz);
        }
        // 为null，返回
        if (jsonstring == null) {
            return null;
        }
        // 需要查数据库
        R res = dbFallBack.apply(id);
        // 查得到，构建缓存
        String k = prefix + id;
        if (res != null) {
            stringRedisTemplate.opsForValue().set(k, JSONUtil.toJsonStr(res), timeout, timeUnit);
        }
        // 查不到，存null在缓存中
        else {
            stringRedisTemplate.opsForValue().set(k, "", timeout, timeUnit);
        }
        return res;
    }

    // 解决缓存击穿
    public <T, R> R queryWithMutex
        (String prefix, T id, Class<R> clazz, String lockPrefix, Function<T, R> dbFallBack, long timeout, TimeUnit timeUnit) {
        // 查缓存
        String k = prefix + id;
        String jsonstring = stringRedisTemplate.opsForValue().get(k);
        // 查到，返回
        if (StrUtil.isNotBlank(jsonstring)) {
            return JSONUtil.toBean(jsonstring, clazz);
        }
        if (jsonstring != null) {
            return null;
        }
        // 查不到，加锁
        boolean isLocked = tryLock(lockPrefix + id, String.valueOf(Thread.currentThread().getId()),  timeout, timeUnit);
        if (isLocked) {
            R res = dbFallBack.apply(id);
            if (res == null) {
                stringRedisTemplate.opsForValue().set(k, "", timeout, timeUnit);
            } else {
                stringRedisTemplate.opsForValue().set(k, JSONUtil.toJsonStr(res), timeout, timeUnit);
            }
            unlock(lockPrefix + id, String.valueOf(Thread.currentThread().getId()));
            return res;
        }
        else {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return  queryWithMutex(k, id, clazz, lockPrefix, dbFallBack, timeout, timeUnit);
        }
    }

    private boolean tryLock(String key, String value, Long timeout, TimeUnit timeUnit) {
        Boolean res = stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeout, timeUnit);
        return BooleanUtil.isTrue(res);
    }

    private void unlock(String key, String value) {
        if (stringRedisTemplate.opsForValue().get(key) != null && Objects.equals(stringRedisTemplate.opsForValue().get(key), value)) {
            stringRedisTemplate.delete(key);
        }
    }

    private ExecutorService threadPool = new ThreadPoolExecutor(10, 10, 10L,TimeUnit.MINUTES, new ArrayBlockingQueue<>(100), new ThreadPoolExecutor.CallerRunsPolicy());

    public <T, R> R queryWithLogicalExpire
            (String prefix, T id, Class<R> clazz, String lockPrefix, Function<T, R> dbFallBack, long timeout, TimeUnit timeUnit) {
        String k = prefix + id;
        // 查询缓存
        String jsonstring = stringRedisTemplate.opsForValue().get(k);
        // 有数据而且没过期，则返回
        if (StrUtil.isNotBlank(jsonstring)) {
            RedisData redisData = JSONUtil.toBean(jsonstring, RedisData.class);
            if (LocalDateTime.now().isBefore(redisData.getExpireTime())) {
                JSONObject data = (JSONObject) redisData.getData();
                R res = JSONUtil.toBean(data, clazz);
                return res;
            }
        }
        // 否则，上锁
        boolean isLocked = tryLock(lockPrefix + id, java.lang.String.valueOf(Thread.currentThread().getId()), timeout, timeUnit);
        try {
            // 获锁成功，开线程来构建缓存
            if (isLocked) {
                threadPool.submit(() -> {
                    saveData2Redis(id, LocalDateTime.now().plusSeconds(timeout));
                });
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockPrefix + id, java.lang.String.valueOf(Thread.currentThread().getId()));
        }
        // 不管是否上锁成功，都返回过期数据
        RedisData redisData = JSONUtil.toBean(jsonstring, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R res = JSONUtil.toBean(data, clazz);
        return res;
    }

    private <T1, T2> void saveData2Redis(T1 id, T2 expireTime) {

    }

}
