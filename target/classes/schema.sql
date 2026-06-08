-- Customer Service Session Allocation System Schema

CREATE DATABASE IF NOT EXISTS customer_service DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE customer_service;

-- Skill groups
CREATE TABLE cs_skill_group (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL UNIQUE,
    description VARCHAR(256) DEFAULT '',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Agents (customer service representatives)
CREATE TABLE cs_agent (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL UNIQUE,
    display_name    VARCHAR(128) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'OFFLINE' COMMENT 'ONLINE|BUSY|AWAY|OFFLINE',
    max_concurrent  INT          NOT NULL DEFAULT 5,
    current_load    INT          NOT NULL DEFAULT 0,
    is_supervisor   TINYINT(1)   NOT NULL DEFAULT 0,
    last_online_at  DATETIME     DEFAULT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Agent-skill group mapping
CREATE TABLE cs_agent_skill (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id       BIGINT NOT NULL,
    skill_group_id BIGINT NOT NULL,
    proficiency    INT    NOT NULL DEFAULT 1 COMMENT 'Skill proficiency 1-5',
    UNIQUE KEY uk_agent_skill (agent_id, skill_group_id),
    FOREIGN KEY (agent_id) REFERENCES cs_agent(id),
    FOREIGN KEY (skill_group_id) REFERENCES cs_skill_group(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Customers
CREATE TABLE cs_customer (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_uid  VARCHAR(64)  NOT NULL UNIQUE COMMENT 'External customer identifier',
    name          VARCHAR(128) NOT NULL DEFAULT '',
    vip_level     VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL|SILVER|GOLD|DIAMOND',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Sessions
CREATE TABLE cs_session (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_no      VARCHAR(64)  NOT NULL UNIQUE COMMENT 'Business session number',
    customer_id     BIGINT       NOT NULL,
    agent_id        BIGINT       DEFAULT NULL,
    skill_group_id  BIGINT       DEFAULT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'QUEUING' COMMENT 'QUEUING|ACTIVE|SUSPENDED|TRANSFERRING|CLOSED',
    vip_level       VARCHAR(20)  NOT NULL DEFAULT 'NORMAL',
    queue_start_at  DATETIME     DEFAULT NULL,
    assign_at       DATETIME     DEFAULT NULL,
    close_at        DATETIME     DEFAULT NULL,
    close_reason    VARCHAR(64)  DEFAULT NULL COMMENT 'NORMAL|TIMEOUT|TRANSFER|SYSTEM',
    last_active_at  DATETIME     DEFAULT NULL COMMENT 'Last message timestamp',
    metadata        JSON         DEFAULT NULL COMMENT 'Extra session context',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_customer (customer_id),
    INDEX idx_agent (agent_id),
    INDEX idx_status (status),
    INDEX idx_queue_start (queue_start_at),
    FOREIGN KEY (customer_id) REFERENCES cs_customer(id),
    FOREIGN KEY (agent_id) REFERENCES cs_agent(id),
    FOREIGN KEY (skill_group_id) REFERENCES cs_skill_group(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Messages
CREATE TABLE cs_message (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_uid   VARCHAR(64)  NOT NULL UNIQUE COMMENT 'Client-generated dedup key',
    session_id    BIGINT       NOT NULL,
    sender_type   VARCHAR(20)  NOT NULL COMMENT 'CUSTOMER|AGENT|SYSTEM',
    sender_id     BIGINT       DEFAULT NULL,
    content_type  VARCHAR(20)  NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT|IMAGE|FILE|SYSTEM',
    content       TEXT         NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'SENT' COMMENT 'SENT|DELIVERED|READ|FAILED',
    sequence_no   BIGINT       NOT NULL DEFAULT 0 COMMENT 'Monotonic seq within session',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session_seq (session_id, sequence_no),
    INDEX idx_message_uid (message_uid),
    FOREIGN KEY (session_id) REFERENCES cs_session(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Queue entries (active queue snapshot, rows removed on dequeue)
CREATE TABLE cs_queue_entry (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT      NOT NULL UNIQUE,
    customer_id     BIGINT      NOT NULL,
    skill_group_id  BIGINT      DEFAULT NULL,
    vip_level       VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    priority_score  INT         NOT NULL DEFAULT 0,
    enqueue_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_priority (priority_score DESC, enqueue_at ASC),
    FOREIGN KEY (session_id) REFERENCES cs_session(id),
    FOREIGN KEY (customer_id) REFERENCES cs_customer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Allocation logs
CREATE TABLE cs_allocation_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT      NOT NULL,
    from_agent_id   BIGINT      DEFAULT NULL,
    to_agent_id     BIGINT      DEFAULT NULL,
    action          VARCHAR(32) NOT NULL COMMENT 'ASSIGN|TRANSFER|TAKEOVER|REBALANCE',
    reason          VARCHAR(256) DEFAULT '',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session (session_id),
    FOREIGN KEY (session_id) REFERENCES cs_session(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Audit logs
CREATE TABLE cs_audit_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_type VARCHAR(20)  NOT NULL COMMENT 'AGENT|SUPERVISOR|SYSTEM|CUSTOMER',
    operator_id   BIGINT       DEFAULT NULL,
    action        VARCHAR(64)  NOT NULL,
    target_type   VARCHAR(32)  DEFAULT NULL COMMENT 'SESSION|AGENT|MESSAGE',
    target_id     BIGINT       DEFAULT NULL,
    detail        JSON         DEFAULT NULL,
    ip_address    VARCHAR(64)  DEFAULT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_operator (operator_type, operator_id),
    INDEX idx_target (target_type, target_id),
    INDEX idx_action (action),
    INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Initial data: default skill groups
INSERT INTO cs_skill_group (name, description) VALUES
    ('general', 'General inquiry'),
    ('technical', 'Technical support'),
    ('billing', 'Billing and payment'),
    ('complaint', 'Complaints handling');
