package com.syit.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.entity.SeckillVoucher;
import com.syit.hmdp.entity.Voucher;
import com.syit.hmdp.mapper.VoucherMapper;
import com.syit.hmdp.service.ISeckillVoucherService;
import com.syit.hmdp.service.IVoucherService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.List;

import static com.syit.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.syit.hmdp.utils.RedisConstants.SECKILL_V2_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @PostConstruct
    private void preloadSeckillStock() {
        List<SeckillVoucher> seckillVouchers = seckillVoucherService.list();
        for (SeckillVoucher seckillVoucher : seckillVouchers) {
            if (seckillVoucher.getVoucherId() == null || seckillVoucher.getStock() == null) {
                continue;
            }
            stringRedisTemplate.opsForValue().set(
                    // SECKILL_STOCK_KEY + seckillVoucher.getVoucherId(),
                    SECKILL_V2_STOCK_KEY + seckillVoucher.getVoucherId(),
                    seckillVoucher.getStock().toString()
            );
        }
    }

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        // 保存到redis中
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString());
    }
}
