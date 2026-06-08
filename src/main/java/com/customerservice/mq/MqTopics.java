package com.customerservice.mq;

/**
 * Constants for MQ topic names used across the system.
 */
public final class MqTopics {
    public static final String SESSION_CREATED = "session.created";
    public static final String SESSION_ASSIGNED = "session.assigned";
    public static final String SESSION_TRANSFERRED = "session.transferred";
    public static final String SESSION_CLOSED = "session.closed";
    public static final String MESSAGE_SENT = "message.sent";
    public static final String AGENT_STATUS_CHANGED = "agent.status.changed";
    public static final String QUEUE_UPDATED = "queue.updated";

    private MqTopics() {}
}
