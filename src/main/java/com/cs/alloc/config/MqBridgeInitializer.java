package com.cs.alloc.config;

import com.cs.alloc.service.MessageQueue;
import com.cs.alloc.ws.WsEventPusher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MqBridgeInitializer implements ApplicationRunner {
    private final MessageQueue messageQueue;
    private final WsEventPusher wsEventPusher;

    @Override
    public void run(ApplicationArguments args) {
        wsEventPusher.initMqBridge(messageQueue);
        log.info("MQ → WebSocket 事件桥接已启动");
    }
}
