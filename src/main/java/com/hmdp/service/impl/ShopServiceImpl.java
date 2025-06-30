package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryShopById(Long id) {
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("404");
        } else {
            return Result.ok(shop);
        }
    }

    // 解决缓存穿透
    public Result queryShopByIdWithPassThrough(Long id) {
        // 查缓存
        String shopKey = CACHE_SHOP_KEY + id;
        String shopJSON = stringRedisTemplate.opsForValue().get(shopKey);
        // 查到，返回
        if (StrUtil.isNotBlank(shopJSON)) {
            return Result.ok(JSONUtil.toBean(shopJSON, Shop.class));
        }
        if (shopJSON != null) {
            return Result.fail("店铺不存在");
        }
        // 查不到，查数据库
        Shop shop = getById(id);
        // 数据库也查不到，存null值
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(""));
        }
        // 构建缓存
        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop));
        stringRedisTemplate.expire(shopKey, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }

    // 解决缓存击穿
    public Result queryShopByIdWithMutex(Long id) {
        // 查缓存
        String shopKey = CACHE_SHOP_KEY + id;
        String shopJSON = stringRedisTemplate.opsForValue().get(shopKey);
        // 查到，返回
        if (StrUtil.isNotBlank(shopJSON)) {
            return Result.ok(JSONUtil.toBean(shopJSON, Shop.class));
        }
        if (shopJSON != null) {
            return Result.fail("店铺不存在");
        }
        // 查不到，加锁
        try {
            // 加锁成功，查数据库，构建缓存
            if (tryLock(LOCK_SHOP_KEY + id, String.valueOf(Thread.currentThread().getId()), LOCK_SHOP_TTL, TimeUnit.SECONDS)) {
                Shop shop = getById(id);
                // 数据库也查不到，存null值
                if (shop == null) {
                    stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(""));
                }
                // 构建缓存
                stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return Result.ok(shop);
            }
            // 加锁失败，休眠一会，重试
            else {
                Thread.sleep(50L);
                return queryShopByIdWithMutex(id);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(LOCK_SHOP_KEY + id, String.valueOf(Thread.currentThread().getId()));
        }
    }

    private ExecutorService threadPool = new ThreadPoolExecutor(10, 10, 10L,TimeUnit.MINUTES, new ArrayBlockingQueue<>(100), new ThreadPoolExecutor.CallerRunsPolicy());

    // 解决缓存击穿用逻辑过期
    public Result queryShopByIdWithLogicalExpire(Long id) {
        String shopKey = CACHE_SHOP_KEY + id;
        // 查询缓存
        String shopJSON = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 有数据而且没过期，则返回
        if (StrUtil.isNotBlank(shopJSON)) {
            RedisData redisData = JSONUtil.toBean(shopJSON, RedisData.class);
            if (LocalDateTime.now().isBefore(redisData.getExpireTime())) {
                JSONObject data = (JSONObject) redisData.getData();
                Shop shop = JSONUtil.toBean(data, Shop.class);
                return Result.ok(shop);
            }
        }
        // 否则，上锁
        boolean isLocked = tryLock(LOCK_SHOP_KEY + id, String.valueOf(Thread.currentThread().getId()), LOCK_SHOP_TTL, TimeUnit.SECONDS);
        try {
            // 获锁成功，开线程来构建缓存
            if (isLocked) {
                threadPool.submit(() -> {
                    saveShop2Redis(id, Long.valueOf(20L));
                });
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            unlock(LOCK_SHOP_KEY + id, String.valueOf(Thread.currentThread().getId()));
        }
        // 不管是否上锁成功，都返回过期数据
        RedisData redisData = JSONUtil.toBean(shopJSON, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        return Result.ok(shop);
    }

    private void saveShop2Redis(Long id, Long expireTime) {

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

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        if (shop == null) {
            return Result.fail("404");
        }
        // 更新数据库
        updateById(shop);
        // 删除缓存
        String shopKey = CACHE_SHOP_KEY + shop.getId();
        stringRedisTemplate.delete(shopKey);
        return Result.ok();
    }
}
