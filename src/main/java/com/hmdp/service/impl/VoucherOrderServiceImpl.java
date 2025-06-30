package com.hmdp.service.impl;

import cn.hutool.core.thread.ThreadFactoryBuilder;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.*;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> REDISSCRIPT = new DefaultRedisScript<>();

    static {
        REDISSCRIPT.setResultType(Long.class);
        REDISSCRIPT.setLocation(new ClassPathResource("seckill"));
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 10L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1024 * 1024),
            new ThreadPoolExecutor.CallerRunsPolicy());

    private BlockingQueue<VoucherOrder> voucherOrderBlockingQueue = new ArrayBlockingQueue<>(1024 * 1024);

    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new OrderTask());
    }

    private class OrderTask implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    VoucherOrder voucherOrder = voucherOrderBlockingQueue.poll();
                    createOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("<UNK>", e);
                }
            }
        }
    }

    @Override
    // lua脚本替代数据库查询
    public Result seckillVoucher(Long voucherId)  {
        // 查询优惠券
        Voucher voucher = voucherService.getById(voucherId);
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        Long userId = UserHolder.getUser().getId();
        // 抢购还没有开始
        if (LocalDateTime.now().isBefore(seckillVoucher.getBeginTime())) {
            return Result.fail("<UNK>");
        }
        // 抢购已经结束
        if (LocalDateTime.now().isAfter(seckillVoucher.getEndTime())) {
            return Result.fail("<UNK>");
        }
        Long res = stringRedisTemplate.execute(
                REDISSCRIPT,
                Arrays.asList(SECKILL_STOCK_KEY + voucherId, SECKILL_ORDER_KEY + voucherId),
                userId
        );
        if (res == 0) {
            return Result.fail("库存不足");
        } else if (res == 1) {
            return Result.fail("重复下单");
        } else {
            // 异步创建订单
            RedisIdWorker redisIdWorker = new RedisIdWorker();
            long orderId = redisIdWorker.nextId(SECKILL_ORDER_KEY);
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            // 放到阻塞队列里
            voucherOrderBlockingQueue.add(voucherOrder);
            // 返回订单id
            return Result.ok(orderId);
        }
    }

    @Transactional
    public Result createOrder(VoucherOrder voucherOrder) {
        // 扣减库存）
        seckillVoucherService.update().setSql("stock = stock - 1").update();
        save(voucherOrder);
        return Result.ok();
    }



    @Transactional
    public Result createOrder2(Long voucherId, Voucher voucher) {
        // 判断订单是否存在
        VoucherOrder order = query().eq("voucherId", voucherId).eq("userId", UserHolder.getUser().getId()).one();
        // 存在，返回
        if (order != null) {
            return Result.fail("<UNK>");
        }
        // 不存在，
        // 库存充足+抢购（扣减库存）
        boolean isSucess = seckillVoucherService.update().setSql("stock = stock - 1").gt("stock", 1).update();
        if (!isSucess) {
            return Result.fail("<UNK>");
        }
        // 生成订单
        // 主键id（订单id）
        long orderId = redisIdWorker.nextId(voucher.getTitle());
        // 用户id
        Long userId = UserHolder.getUser().getId();
        // 优惠券id已经有了
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);
    }


    // 解决库存超卖
//    public Result seckillVoucher1(Long voucherId) {
//        // 查询优惠券
//        Voucher voucher = voucherService.getById(voucherId);
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        // 抢购还没有开始
//        if (LocalDateTime.now().isBefore(seckillVoucher.getBeginTime())) {
//            return Result.fail("<UNK>");
//        }
//        // 抢购已经结束
//        if (LocalDateTime.now().isAfter(seckillVoucher.getEndTime())) {
//            return Result.fail("<UNK>");
//        }
//        // 库存不足
//        if (seckillVoucher.getStock() <= 0) {
//            return Result.fail("<UNK>");
//        }
//        // 库存充足+抢购（扣减库存）
//        boolean isSucess = seckillVoucherService.update().setSql("stock = stock - 1").gt("stock", 1).update();
//        if (!isSucess) {
//            return Result.fail("<UNK>");
//        }
//        // 生成订单
//        // 主键id（订单id）
//        long orderId = redisIdWorker.nextId(voucher.getTitle());
//        // 用户id
//        Long userId = UserHolder.getUser().getId();
//        // 优惠券id已经有了
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
//        save(voucherOrder);
//        return Result.ok(orderId);
//    }

    // 解决1人1单，未加锁
//    public Result seckillVoucher2(Long voucherId)  {
//        // 查询优惠券
//        Voucher voucher = voucherService.getById(voucherId);
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        // 抢购还没有开始
//        if (LocalDateTime.now().isBefore(seckillVoucher.getBeginTime())) {
//            return Result.fail("<UNK>");
//        }
//        // 抢购已经结束
//        if (LocalDateTime.now().isAfter(seckillVoucher.getEndTime())) {
//            return Result.fail("<UNK>");
//        }
//        // 库存不足
//        if (seckillVoucher.getStock() <= 0) {
//            return Result.fail("<UNK>");
//        }
//        // 判断订单是否存在
//        return createOrder(voucherId, voucher);
//    }

    // 解决1人1单，加synchronized
//    public Result seckillVoucher(Long voucherId)  {
//        // 查询优惠券
//        Voucher voucher = voucherService.getById(voucherId);
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        // 抢购还没有开始
//        if (LocalDateTime.now().isBefore(seckillVoucher.getBeginTime())) {
//            return Result.fail("<UNK>");
//        }
//        // 抢购已经结束
//        if (LocalDateTime.now().isAfter(seckillVoucher.getEndTime())) {
//            return Result.fail("<UNK>");
//        }
//        // 库存不足
//        if (seckillVoucher.getStock() <= 0) {
//            return Result.fail("<UNK>");
//        }
//        Long userId = UserHolder.getUser().getId();
//        synchronized (userId.toString().intern()) {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createOrder(voucherId, voucher);
//        }
//    }

//    // 解决一人一单，用redis分布式锁
//    public Result seckillVoucher(Long voucherId)  {
//        // 查询优惠券
//        Voucher voucher = voucherService.getById(voucherId);
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        // 抢购还没有开始
//        if (LocalDateTime.now().isBefore(seckillVoucher.getBeginTime())) {
//            return Result.fail("<UNK>");
//        }
//        // 抢购已经结束
//        if (LocalDateTime.now().isAfter(seckillVoucher.getEndTime())) {
//            return Result.fail("<UNK>");
//        }
//        // 库存不足
//        if (seckillVoucher.getStock() <= 0) {
//            return Result.fail("<UNK>");
//        }
//        Long userId = UserHolder.getUser().getId();
//        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, userId.toString());
//        boolean isLocked = lock.tryLock(LOCK_SHOP_TTL, TimeUnit.SECONDS);
//        if (!isLocked) {
//            return Result.fail("<UNK>");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createOrder(voucherId, voucher);
//        } finally {
//            lock.unlock();
//        }

//    }
}
