package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 王雄俊
 * @since 2024-01-02
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryTypeList() {

        //查询Redis是否有缓存
        Long size = stringRedisTemplate.opsForList().size(CACHE_SHOP_TYPE_KEY);

        List<String> typeList_String;
        List<ShopType> typeList;//存储商品类型的List


        //有数据，直接返回数据
        if (size != 0){
            typeList_String = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, size);
            //将每个String都转换回ShopType类
            typeList = typeList_String.stream().map(s->{
                ShopType shopType = JSONUtil.toBean(s, ShopType.class);
                return shopType;
            }).collect(Collectors.toList());
            return typeList;
        }
        //没有缓存就查询数据库
        typeList = this.query().orderByAsc("sort").list();

        if (typeList == null || typeList.isEmpty()){
            //数据库中不存在，返回null
            return null;
        }
        //数据库里面有，就存入Redis缓存，并返回这个List
        typeList_String = typeList.stream().map(shopType -> {
            return JSONUtil.toJsonStr(shopType);
        }).collect(Collectors.toList());
        //存入Redis
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY, typeList_String);
        //返回
        return typeList;
    }
}
