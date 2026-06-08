package com.cs.alloc.config;

import com.cs.alloc.service.InMemoryMessageQueue;
import com.cs.alloc.service.MessageQueue;
import com.cs.alloc.service.RedisMessageQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Slf4j
@Configuration
public class AppConfig {
    @Value("${cs.mq.type:memory}")
    private String mqType;

    @Bean
    public MessageQueue messageQueue(StringRedisTemplate redisTemplate, RedisConnectionFactory connectionFactory) {
        if ("redis".equalsIgnoreCase(mqType)) {
            log.info("使用 Redis Pub/Sub 消息队列");
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            container.afterPropertiesSet();
            return new RedisMessageQueue(redisTemplate, container);
        }
        log.info("使用内存消息队列");
        return new InMemoryMessageQueue();
    }
}
