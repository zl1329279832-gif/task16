package com.customerservice.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * In-memory message queue implementation for development and testing.
 * Replace with RabbitMQ / Kafka adapter in production by implementing MessageQueue interface.
 */
@Component
public class InMemoryMessageQueue implements MessageQueue {
    private static final Logger log = LoggerFactory.getLogger(InMemoryMessageQueue.class);

    private final Map<String, List<Consumer<Object>>> subscribers = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @Override
    public void publish(String topic, Object message) {
        log.debug("MQ publish [{}]: {}", topic, message);
        List<Consumer<Object>> consumers = subscribers.get(topic);
        if (consumers != null) {
            for (Consumer<Object> consumer : consumers) {
                executor.submit(() -> {
                    try {
                        consumer.accept(message);
                    } catch (Exception e) {
                        log.error("MQ consumer error on topic [{}]", topic, e);
                    }
                });
            }
        }
    }

    @Override
    public void subscribe(String topic, Consumer<Object> consumer) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(consumer);
        log.info("MQ subscribed to topic [{}]", topic);
    }

    @Override
    public void unsubscribe(String topic, Consumer<Object> consumer) {
        List<Consumer<Object>> consumers = subscribers.get(topic);
        if (consumers != null) {
            consumers.remove(consumer);
        }
    }
}
