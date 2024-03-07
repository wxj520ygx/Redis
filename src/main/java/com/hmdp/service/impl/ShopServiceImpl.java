package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 王雄俊
 * @since 2024-01-02
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //缓存穿透的代码调用
        //这里的this::getById其实就是lambda表达式：id2->getById(id2)，写高级点
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);
        //Shop shop = queryWithLogicalExpire(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        //返回
        return Result.ok(shop);
    }

     private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从Redis中查询商铺缓存，存储对象可以用String或者Hash，这里用String
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //未命中，直接返回
            return null;
        }
        //命中，先把json反序列化成对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();//注意我们获取到了data信息以后，返回的会是一个JSONObject的格式
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //未过期，直接返回店铺信息
            return shop;
        }
        //已过期，缓存重建
        //获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;//LOCK_SHOP_KEY="lock:shop:"
        boolean isLock = tryLock(lockKey);
        //判断是否获取锁成功
        if (isLock){
            //成功获取锁，开启独立线程来实现缓存重建，用线程池来做
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //没有成功获取，直接返回过期商铺信息
        return shop;
    }

//    public Shop queryWithMutex(Long id){
//        String key = CACHE_SHOP_KEY + id;
//        //从Redis中查询商铺缓存，存储对象可以用String或者Hash，这里用String
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //判断是否存在
//        if (StrUtil.isNotBlank(shopJson)) {
//            //存在，直接返回
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        //判断命中的是否是null
//        if (shopJson != null){
//            //返回错误信息
//            return null;
//        }
//        //未命中，开始实现缓存重建
//        //尝试获取互斥锁，锁的key跟缓存的key不一样
//        String lockKey = "lock:shop:" + id;
//        Shop shop = null;
//        try {
//            boolean isLock = tryLock(lockKey);
//            //判断是否获取成功
//            if (!isLock){//失败则休眠并重试（递归）
//                Thread.sleep(50);//单位：ms
//                //如果担心递归造成爆栈，可以用循环，一样的
//                return queryWithMutex(id);
//            }
//            //成功就直接开始重建（查询数据库并处理后保存到Redis）
//            shop = getById(id);
//            //由于在这里我们的数据库在本地，重建很快，所以添加休眠时间来模拟数据库重建过程的耗时长，方便测试
//            Thread.sleep(200);
//            //不存在，返回错误
//            if (shop == null){
//                //存一个null到Redis中
//                //这种没用的信息，TTL没必要设置太长了，这里我设置成了2min
//                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;
//            }
//            //存在，写入Redis
//            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }finally {
//            //释放互斥锁
//            unlock(lockKey);
//        }
//        //返回
//        return shop;
//    }

//    public Shop queryWithPassThrough(Long id){
//        String key = CACHE_SHOP_KEY + id;
//        //从Redis中查询商铺缓存，存储对象可以用String或者Hash，这里用String
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //判断是否存在
//        if (StrUtil.isNotBlank(shopJson)) {
//            //存在，直接返回
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return shop;
//        }
//        //判断命中的是否是null
//        if (shopJson != null){
//            //返回错误信息
//            return null;
//        }
//        //不存在，根据id查询数据库
//        Shop shop = getById(id);
//        //不存在，返回错误
//        if (shop == null){
//            //存一个null到Redis中
//            //这种没用的信息，TTL没必要设置太长了，这里我设置成了2min
//            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        //存在，写入Redis
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        //返回
//        return shop;
//    }

    private boolean tryLock(String key){
        //opsForValue里面没有真正的setNx，而是setIfAbsent，表示如果不存在就执行set
        //值就随便设定一下，重点是要获取到锁，但是设定了TTL为10s
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        /**
         * 如果是直接返回flag，可能会有拆箱操作，造成空指针，需要用BooleanUtil工具类
         * 因为Boolean不是基本类型的boolean，是boolean的封装类
         */
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        //注入Shop对象
        redisData.setData(shop);
        //注入逻辑过期时间
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入Redis，不要设置TTL
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional  //如果操作中有异常，我们用该注解实现事务回滚
    public Result update(Shop shop) {
        //我们要首先对id进行判断
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存 这里用了单事务，将删除缓存与更新数据库放在了一个事务里面
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //判断是否是要根据坐标查询
        if(x == null || y == null){
            //不需要根据坐标查询，说明不是按照距离排序，直接查询数据库
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        //计算分页参数
        int start = (current - 1) * DEFAULT_BATCH_SIZE;
        int end = current * DEFAULT_BATCH_SIZE;
        //查询Redis，按照距离来进行排序、分页，结果：shopId与distance
        String key = SHOP_GEO_KEY + typeId;//SHOP_GEO_KEY = "shop:geo:"
        //GEOSEARCH key BYLONLAT x y BYRADIUS 5000 WITHDISTANCE
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),//方圆5公里以内的店铺
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance()//WITHDISTANCE
                                .limit(end)//查询到的结果还要满足分页的情况，但是只能指定[0,end]，剩下要逻辑分页
                );
        if(results == null){
            return Result.ok(Collections.emptyList());
        }
        //解析出id
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        //System.out.println(list);
        if(list.size() <= start){
            //没有下一页了，没办法执行skip，直接返回
            return Result.ok(Collections.emptyList());
        }

        //收集Long类型的店铺id
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());

        //截取start到end的分页部分，可以用stream的skip，效率更高
        list.stream().skip(start).forEach(result -> {
            //获取店铺id
            String shopIdStr = result.getContent().getName();
            //收集起来
            ids.add(Long.valueOf(shopIdStr));
            //获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        //System.out.println(distanceMap);
        //根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        //需要将距离参数传入shops，返还到前端
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        return Result.ok(shops);
    }
}
