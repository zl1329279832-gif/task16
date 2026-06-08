package com.customerservice.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Unified WebSocket event envelope.
 * All WS messages follow this structure.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WsEvent {
    /** Event type: QUEUE_POSITION, MESSAGE, SESSION_ASSIGNED, SESSION_TRANSFERRED,
     *  SESSION_CLOSED, AGENT_STATUS, SYSTEM_NOTICE, ERROR, HEARTBEAT */
    private String event;
    private Long sessionId;
    private Object data;
    private Long timestamp;

    public static WsEvent of(String event, Long sessionId, Object data) {
        return WsEvent.builder()
                .event(event)
                .sessionId(sessionId)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static WsEvent of(String event, Object data) {
        return of(event, null, data);
    }
}
