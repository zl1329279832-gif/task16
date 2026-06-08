package com.customerservice;

import com.customerservice.model.enums.VipLevel;
import com.customerservice.service.QueueService;
import com.customerservice.mapper.QueueEntryMapper;
import com.customerservice.websocket.WebSocketSessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    @Mock
    private QueueEntryMapper queueEntryMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private WebSocketSessionManager wsSessionManager;

    @InjectMocks
    private QueueService queueService;

    @Test
    void calculatePriority_normalCustomerNoWait() {
        int score = queueService.calculatePriority(VipLevel.NORMAL, 0);
        assertEquals(0, score);
    }

    @Test
    void calculatePriority_diamondCustomerNoWait() {
        int score = queueService.calculatePriority(VipLevel.DIAMOND, 0);
        assertEquals(30, score);
    }

    @Test
    void calculatePriority_normalCustomerLongWait() {
        // 5 minutes = 300 seconds -> 300/30 = 10 aging bonus
        int score = queueService.calculatePriority(VipLevel.NORMAL, 300);
        assertEquals(10, score);
    }

    @Test
    void calculatePriority_vipShouldBeatNormalEvenWithWait() {
        // Diamond with no wait
        int diamondScore = queueService.calculatePriority(VipLevel.DIAMOND, 0);
        // Normal with 5 minutes wait
        int normalScore = queueService.calculatePriority(VipLevel.NORMAL, 300);

        assertTrue(diamondScore > normalScore,
                "VIP Diamond (30) should score higher than Normal+5min wait (10)");
    }

    @Test
    void calculatePriority_normalEventuallyBeatsVipWithTime() {
        // Normal with 20 minutes wait = 20*60/30 = 40
        int normalLongWait = queueService.calculatePriority(VipLevel.NORMAL, 1200);
        // Silver with no wait = 10
        int silverNoWait = queueService.calculatePriority(VipLevel.SILVER, 0);

        assertTrue(normalLongWait > silverNoWait,
                "Normal with 20min wait (40) should eventually beat Silver (10)");
    }

    @Test
    void calculatePriority_goldWithMediumWait() {
        // Gold(20) + 3 min wait(6) = 26
        int score = queueService.calculatePriority(VipLevel.GOLD, 180);
        assertEquals(26, score);
    }

    @Test
    void calculatePriority_nullVipLevel() {
        int score = queueService.calculatePriority(null, 60);
        assertEquals(2, score); // just aging: 60/30 = 2
    }
}
