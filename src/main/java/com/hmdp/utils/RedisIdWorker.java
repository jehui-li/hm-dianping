package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    @Resource
    private static StringRedisTemplate stringRedisTemplate;

    public long nextId(String key) {
        // 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - getBeginSecond();
        String day = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        // redis生成全局唯一id
        Long increment = stringRedisTemplate.opsForValue().increment(key + day);
        return timeStamp << 32 | increment;
    }

    private long getBeginSecond() {
        return LocalDateTime.of(2020, 7, 8, 0, 0, 0).toEpochSecond(ZoneOffset.UTC);
    }

}
