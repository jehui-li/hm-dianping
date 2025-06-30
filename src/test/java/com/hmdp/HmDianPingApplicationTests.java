package com.hmdp;

import org.junit.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;


@SpringBootTest
public class HmDianPingApplicationTests {
    private StringRedisTemplate stringRedisTemplate;
 @Test
 public void test() {
     System.out.println(stringRedisTemplate.opsForValue().increment("1"));
 }

}
