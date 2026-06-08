package com.cs.alloc.service;

import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
public class InMemoryMessageQueue implements MessageQueue {
    private final Map<String, List<Consumer<String>>> subscribers = new ConcurrentHashMap<>();

    @Override
    public void publish(String topic, String message) {
        log.debug("[MQ-MEM] publish topic={} msg={}", topic, message);
        List<Consumer<String>> consumers = subscribers.get(topic);
        if (consumers != null) {
            for (Consumer<String> consumer : consumers) {
                try { consumer.accept(message); } catch (Exception e) {
                    log.error("[MQ-MEM] consumer error on topic={}", topic, e);
                }
            }
        }
    }

    @Override
    public void subscribe(String topic, Consumer<String> consumer) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(consumer);
        log.info("[MQ-MEM] subscribed to topic={}", topic);
    }
}
