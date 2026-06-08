package com.cs.alloc.config;

import com.cs.alloc.ws.CustomerWebSocketHandler;
import com.cs.alloc.ws.AgentWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final CustomerWebSocketHandler customerHandler;
    private final AgentWebSocketHandler agentHandler;

    public WebSocketConfig(CustomerWebSocketHandler customerHandler, AgentWebSocketHandler agentHandler) {
        this.customerHandler = customerHandler;
        this.agentHandler = agentHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(customerHandler, "/ws/customer").setAllowedOrigins("*");
        registry.addHandler(agentHandler, "/ws/agent").setAllowedOrigins("*");
    }
}
