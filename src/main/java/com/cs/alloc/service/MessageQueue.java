package com.cs.alloc.service;

import java.util.function.Consumer;

public interface MessageQueue {
    void publish(String topic, String message);
    void subscribe(String topic, Consumer<String> consumer);

    interface Topics {
        String SESSION_ALLOCATED  = "session.allocated";
        String SESSION_TRANSFERRED = "session.transferred";
        String SESSION_CLOSED     = "session.closed";
        String QUEUE_UPDATED      = "queue.updated";
        String AGENT_STATUS       = "agent.status";
        String CHAT_MESSAGE       = "chat.message";
        String SYSTEM_NOTICE      = "system.notice";
        String SLA_RISK_CHANGED   = "sla.risk.changed";
        String QUEUE_REORDERED    = "queue.reordered";
    }
}
