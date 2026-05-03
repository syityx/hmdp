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

import javax.annotation.PostConstruct;
import java.util.List;
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

    /**
     * 服务启动时预热 Shop 缓存到 Redis，配合 queryWithLogicalExpire 使用。
     * 逻辑过期方案不设物理 TTL，缓存永不自动淘汰，所以必须预热保证命中。
     */
    @PostConstruct
    public void preheatShopCache() {
        System.out.println("=== 开始预热店铺缓存 ===");
        List<Shop> shops = list();
        for (Shop shop : shops) {
            cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + shop.getId(), shop,
                    5L, TimeUnit.SECONDS);
        }
        System.out.println("=== 店铺缓存预热完成，共 " + shops.size() + " 条 ===");
    }

    // 重点是解决 缓存击穿 和 缓存穿透
    @Override
    public Result queryById(Long id) {
        // ============= 无缓存 =============
        // Shop shop = getById(id);     // 直接查询数据库，测试缓存击穿和穿透问题

        // ============= 使用缓存，不解决缓存击穿问题 =============
        // Shop shop = cacheClient.queryWithRedis(
        //     CACHE_SHOP_KEY, id, Shop.class, 
        //     this::getById, 5L, TimeUnit.SECONDS
        // );      // 只使用缓存,没有命中缓存就查询数据库,并将结果写入缓存,解决缓存穿透

        // ============= 不同方案解决缓存击穿： =============
        // synchronzied             reentrantlock               redisson分布式锁            逻辑过期
        // queryWithSynchronzied    queryWithReentrantLock      queryWithRedissonLock   queryWithLogicalExpire
        Shop shop = cacheClient.queryWithRedissonLock(
            CACHE_SHOP_KEY, id, Shop.class, 
            this::getById, 5L, TimeUnit.SECONDS
        );      // 时间设置为5s过期，方便观察缓存击穿
        // 注意：使用默认存在缓存预热，如果使用其他方案测试，需要手动删除缓存，否则会因为格式key中格式不同而出错

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        
        return Result.ok(shop);
    }


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
