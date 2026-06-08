-- ================================================================
-- 客服会话分配系统 - 数据库表结构
-- ================================================================

CREATE TABLE customer (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100)  NOT NULL,
    vip_level       INT           NOT NULL DEFAULT 0 COMMENT '0普通 1银卡 2金卡 3白金',
    source          VARCHAR(50)   DEFAULT NULL COMMENT '来源渠道: web / app / wechat',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_vip (vip_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE skill_group (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100)  NOT NULL,
    description     VARCHAR(500)  DEFAULT NULL,
    priority        INT           NOT NULL DEFAULT 0,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100)  NOT NULL,
    skill_group_id  BIGINT        NOT NULL,
    max_capacity    INT           NOT NULL DEFAULT 5,
    is_supervisor   TINYINT(1)    NOT NULL DEFAULT 0,
    status          VARCHAR(20)   NOT NULL DEFAULT 'OFFLINE',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_skill_group (skill_group_id),
    CONSTRAINT fk_agent_skill_group FOREIGN KEY (skill_group_id) REFERENCES skill_group(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_state (
    agent_id        BIGINT PRIMARY KEY,
    current_load    INT           NOT NULL DEFAULT 0,
    last_heartbeat  DATETIME      DEFAULT NULL,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_state FOREIGN KEY (agent_id) REFERENCES agent(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE session (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_no      VARCHAR(64)   NOT NULL,
    customer_id     BIGINT        NOT NULL,
    agent_id        BIGINT        DEFAULT NULL,
    skill_group_id  BIGINT        DEFAULT NULL,
    status          VARCHAR(20)   NOT NULL COMMENT 'WAITING/ASSIGNED/ACTIVE/TRANSFERRING/SUSPENDED/CLOSED',
    transfer_from   BIGINT        DEFAULT NULL,
    priority_score  INT           DEFAULT 0,
    assigned_at     DATETIME      DEFAULT NULL,
    closed_at       DATETIME      DEFAULT NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_session_no (session_no),
    INDEX idx_customer (customer_id),
    INDEX idx_agent (agent_id),
    INDEX idx_status (status),
    INDEX idx_skill_group_status (skill_group_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT        NOT NULL,
    sender_id       VARCHAR(64)   NOT NULL,
    sender_type     VARCHAR(10)   NOT NULL COMMENT 'CUSTOMER/AGENT/SYSTEM',
    content         TEXT          NOT NULL,
    msg_type        VARCHAR(20)   NOT NULL DEFAULT 'TEXT',
    idempotency_key VARCHAR(64)   DEFAULT NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_idempotency (idempotency_key),
    INDEX idx_session_created (session_id, created_at),
    CONSTRAINT fk_msg_session FOREIGN KEY (session_id) REFERENCES session(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE queue_entry (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT        NOT NULL,
    customer_id     BIGINT        NOT NULL,
    skill_group_id  BIGINT        NOT NULL,
    priority_score  INT           NOT NULL DEFAULT 0,
    position        INT           NOT NULL DEFAULT 0,
    joined_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_queue_session (session_id),
    INDEX idx_queue_skill_priority (skill_group_id, priority_score DESC),
    CONSTRAINT fk_queue_session FOREIGN KEY (session_id) REFERENCES session(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE allocation_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT        NOT NULL,
    agent_id        BIGINT        DEFAULT NULL,
    action          VARCHAR(30)   NOT NULL COMMENT 'ALLOCATE/TRANSFER/REASSIGN/TAKEOVER/RELEASE',
    reason          VARCHAR(255)  DEFAULT NULL,
    score_detail    VARCHAR(500)  DEFAULT NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_log_session (session_id),
    INDEX idx_log_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE audit_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_id     VARCHAR(64)   NOT NULL,
    operator_type   VARCHAR(10)   NOT NULL COMMENT 'AGENT/SUPERVISOR/SYSTEM',
    action          VARCHAR(50)   NOT NULL,
    target_type     VARCHAR(50)   DEFAULT NULL,
    target_id       VARCHAR(64)   DEFAULT NULL,
    detail          TEXT          DEFAULT NULL,
    ip_address      VARCHAR(50)   DEFAULT NULL,
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_operator (operator_id),
    INDEX idx_audit_target (target_type, target_id),
    INDEX idx_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO skill_group (id, name, description, priority) VALUES
(1, '通用客服', '处理一般性咨询', 0),
(2, '技术支持', '处理技术类问题', 0),
(3, 'VIP专属',  'VIP客户专属服务', -1);

INSERT INTO agent (id, name, skill_group_id, max_capacity, is_supervisor, status) VALUES
(1, '张三', 1, 5, 0, 'OFFLINE'),
(2, '李四', 1, 5, 0, 'OFFLINE'),
(3, '王五', 2, 3, 0, 'OFFLINE'),
(4, '赵六', 3, 5, 1, 'OFFLINE');

INSERT INTO agent_state (agent_id, current_load) VALUES (1,0),(2,0),(3,0),(4,0);

INSERT INTO customer (id, name, vip_level, source) VALUES
(1, '普通用户A', 0, 'web'),
(2, '金卡用户B', 2, 'app');
