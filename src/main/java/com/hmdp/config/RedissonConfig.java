package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        //配置
        Config config = new Config();
        //添加Redis地址，这里添加的是单点的地址，也可以使用config.userClusterServer()来添加集群的地址
        config.useSingleServer().setAddress("redis://192.168.177.130:6379").setPassword("123456");
        //创建客户端
        return Redisson.create(config);
    }

}
