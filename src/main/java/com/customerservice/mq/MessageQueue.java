package com.customerservice.mq;

import java.util.function.Consumer;

/**
 * Pluggable message queue abstraction.
 * Default implementation is in-memory; can be replaced with RabbitMQ/Kafka/RocketMQ.
 */
public interface MessageQueue {

    /**
     * Publish a message to a topic.
     */
    void publish(String topic, Object message);

    /**
     * Subscribe to a topic with a consumer.
     */
    void subscribe(String topic, Consumer<Object> consumer);

    /**
     * Unsubscribe from a topic.
     */
    void unsubscribe(String topic, Consumer<Object> consumer);
}
