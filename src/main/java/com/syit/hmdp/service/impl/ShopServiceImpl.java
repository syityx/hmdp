package com.syit.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.entity.Shop;
import com.syit.hmdp.mapper.ShopMapper;
import com.syit.hmdp.service.IShopService;
import com.syit.hmdp.utils.CacheClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.syit.hmdp.utils.RedisConstants.*;

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

    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Autowired
    CacheClient cacheClient;

    @Override

    public Result queryById(Long id) {
        // 重点是解决 缓存击穿 和 缓存穿透
        // Shop shop = getById(id);     // 直接查询数据库，测试缓存击穿和穿透问题


        // Shop shop = cacheClient.queryWithRedis(
        //     CACHE_SHOP_KEY, id, Shop.class, 
        //     this::getById, 5L, TimeUnit.SECONDS
        // );      // 只使用缓存,没有命中缓存就查询数据库,并将结果写入缓存,解决缓存穿透

        // queryWithReentrantLock queryWithMutex queryWithRedissonLock
        Shop shop = cacheClient.queryWithReentrantLock(
            CACHE_SHOP_KEY, id, Shop.class, 
            this::getById, 5L, TimeUnit.SECONDS
        );      // 使用互斥锁解决缓存击穿问题,只有一个线程去重建缓存,其他线程等待,解决缓存穿透问题

        



        // A--使用互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);

        // B--解决缓存穿透
//        Shop shop = queryWithPassThrogh(id);

        // A--使用redis工具类解决缓存击穿
        // Shop shop = cacheClient.queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // B--使用redis工具类解决缓存穿透
    //    Shop shop = cacheClient.queryWithPassThrogh(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 互斥锁解决缓存击穿
     * 缓存击穿：热点key过期，导致大量请求打到数据库上
        互斥锁：加锁，单线程重建缓存，其他线程等待。
        只有一个线程（拿到锁的）去重建缓存
     * ==========================  <br>
     * queryWithMutex  <br>
     * 已转移到redis工具类：CacheClient
     * @param id
     * @return shop or null
     */



    // 解决缓存穿透
    /**
     * 有问题：：
     * 重建缓存时间太长，所有线程未命中缓存，全部打到数据库上  <br>
     * 所以要加锁，只能有一个线程去重建缓存 queryWithMutex  <br>
     * ==========================  <br>
     * queryWithPassThrogh  <br>
     * 已转移到redis工具类：CacheClient
     */


    // 添加事务注解，保证数据库和缓存的一致性
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        // 1 更新数据库
        updateById(shop);
        // 2 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());

        return Result.ok(id);
    }
}
