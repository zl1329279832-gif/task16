package com.cs.alloc.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import java.util.function.Consumer;

@Slf4j
public class RedisMessageQueue implements MessageQueue {
    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer container;

    public RedisMessageQueue(StringRedisTemplate redis, RedisMessageListenerContainer container) {
        this.redis = redis;
        this.container = container;
    }

    @Override
    public void publish(String topic, String message) {
        log.debug("[MQ-REDIS] publish topic={} msg={}", topic, message);
        redis.convertAndSend(topic, message);
    }

    @Override
    public void subscribe(String topic, Consumer<String> consumer) {
        MessageListener listener = (msg, pattern) -> {
            try { consumer.accept(new String(msg.getBody())); } catch (Exception e) {
                log.error("[MQ-REDIS] consumer error on topic={}", topic, e);
            }
        };
        container.addMessageListener(listener, new ChannelTopic(topic));
        log.info("[MQ-REDIS] subscribed to topic={}", topic);
    }
}
