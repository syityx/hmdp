package com.syit.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.syit.hmdp.dto.Result;
import com.syit.hmdp.entity.Shop;

/**
 * <p>
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result update(Shop shop);
}
