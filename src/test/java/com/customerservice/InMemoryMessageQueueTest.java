package com.customerservice;

import com.customerservice.mq.InMemoryMessageQueue;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryMessageQueueTest {

    @Test
    void publishAndSubscribe() throws Exception {
        InMemoryMessageQueue mq = new InMemoryMessageQueue();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Object> received = new AtomicReference<>();

        mq.subscribe("test.topic", msg -> {
            received.set(msg);
            latch.countDown();
        });

        mq.publish("test.topic", "hello");

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals("hello", received.get());
    }

    @Test
    void multipleSubscribers() throws Exception {
        InMemoryMessageQueue mq = new InMemoryMessageQueue();
        CountDownLatch latch = new CountDownLatch(2);

        mq.subscribe("multi", msg -> latch.countDown());
        mq.subscribe("multi", msg -> latch.countDown());

        mq.publish("multi", "data");

        assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

    @Test
    void unsubscribe() throws Exception {
        InMemoryMessageQueue mq = new InMemoryMessageQueue();
        CountDownLatch latch = new CountDownLatch(1);
        java.util.function.Consumer<Object> consumer = msg -> latch.countDown();

        mq.subscribe("unsub", consumer);
        mq.unsubscribe("unsub", consumer);
        mq.publish("unsub", "should not receive");

        assertFalse(latch.await(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void publishToNonExistentTopic() {
        InMemoryMessageQueue mq = new InMemoryMessageQueue();
        // Should not throw
        assertDoesNotThrow(() -> mq.publish("no.subscribers", "data"));
    }
}
