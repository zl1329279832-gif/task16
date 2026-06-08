-- H2-compatible schema for tests

CREATE TABLE IF NOT EXISTS cs_skill_group (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL UNIQUE,
    description VARCHAR(256) DEFAULT '',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS cs_agent (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL UNIQUE,
    display_name    VARCHAR(128) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'OFFLINE',
    max_concurrent  INT          NOT NULL DEFAULT 5,
    current_load    INT          NOT NULL DEFAULT 0,
    is_supervisor   TINYINT      NOT NULL DEFAULT 0,
    last_online_at  TIMESTAMP    DEFAULT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS cs_agent_skill (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id       BIGINT NOT NULL,
    skill_group_id BIGINT NOT NULL,
    proficiency    INT    NOT NULL DEFAULT 1,
    UNIQUE (agent_id, skill_group_id),
    FOREIGN KEY (agent_id) REFERENCES cs_agent(id),
    FOREIGN KEY (skill_group_id) REFERENCES cs_skill_group(id)
);

CREATE TABLE IF NOT EXISTS cs_customer (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_uid  VARCHAR(64)  NOT NULL UNIQUE,
    name          VARCHAR(128) NOT NULL DEFAULT '',
    vip_level     VARCHAR(20)  NOT NULL DEFAULT 'NORMAL',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS cs_session (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_no      VARCHAR(64)  NOT NULL UNIQUE,
    customer_id     BIGINT       NOT NULL,
    agent_id        BIGINT       DEFAULT NULL,
    skill_group_id  BIGINT       DEFAULT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'QUEUING',
    vip_level       VARCHAR(20)  NOT NULL DEFAULT 'NORMAL',
    queue_start_at  TIMESTAMP    DEFAULT NULL,
    assign_at       TIMESTAMP    DEFAULT NULL,
    close_at        TIMESTAMP    DEFAULT NULL,
    close_reason    VARCHAR(64)  DEFAULT NULL,
    last_active_at  TIMESTAMP    DEFAULT NULL,
    metadata        CLOB         DEFAULT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (customer_id) REFERENCES cs_customer(id),
    FOREIGN KEY (agent_id) REFERENCES cs_agent(id),
    FOREIGN KEY (skill_group_id) REFERENCES cs_skill_group(id)
);

CREATE TABLE IF NOT EXISTS cs_message (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_uid   VARCHAR(64)  NOT NULL UNIQUE,
    session_id    BIGINT       NOT NULL,
    sender_type   VARCHAR(20)  NOT NULL,
    sender_id     BIGINT       DEFAULT NULL,
    content_type  VARCHAR(20)  NOT NULL DEFAULT 'TEXT',
    content       CLOB         NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'SENT',
    sequence_no   BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES cs_session(id)
);

CREATE TABLE IF NOT EXISTS cs_queue_entry (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT      NOT NULL UNIQUE,
    customer_id     BIGINT      NOT NULL,
    skill_group_id  BIGINT      DEFAULT NULL,
    vip_level       VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    priority_score  INT         NOT NULL DEFAULT 0,
    enqueue_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES cs_session(id),
    FOREIGN KEY (customer_id) REFERENCES cs_customer(id)
);

CREATE TABLE IF NOT EXISTS cs_allocation_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT      NOT NULL,
    from_agent_id   BIGINT      DEFAULT NULL,
    to_agent_id     BIGINT      DEFAULT NULL,
    action          VARCHAR(32) NOT NULL,
    reason          VARCHAR(256) DEFAULT '',
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (session_id) REFERENCES cs_session(id)
);

CREATE TABLE IF NOT EXISTS cs_audit_log (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator_type VARCHAR(20)  NOT NULL,
    operator_id   BIGINT       DEFAULT NULL,
    action        VARCHAR(64)  NOT NULL,
    target_type   VARCHAR(32)  DEFAULT NULL,
    target_id     BIGINT       DEFAULT NULL,
    detail        CLOB         DEFAULT NULL,
    ip_address    VARCHAR(64)  DEFAULT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Seed data
INSERT INTO cs_skill_group (name, description) VALUES ('general', 'General inquiry');
INSERT INTO cs_skill_group (name, description) VALUES ('technical', 'Technical support');
INSERT INTO cs_skill_group (name, description) VALUES ('billing', 'Billing and payment');

INSERT INTO cs_agent (username, display_name, status, max_concurrent, is_supervisor)
    VALUES ('agent1', 'Agent One', 'ONLINE', 5, 0);
INSERT INTO cs_agent (username, display_name, status, max_concurrent, is_supervisor)
    VALUES ('agent2', 'Agent Two', 'ONLINE', 3, 0);
INSERT INTO cs_agent (username, display_name, status, max_concurrent, is_supervisor)
    VALUES ('supervisor1', 'Supervisor One', 'ONLINE', 5, 1);

INSERT INTO cs_agent_skill (agent_id, skill_group_id, proficiency) VALUES (1, 1, 3);
INSERT INTO cs_agent_skill (agent_id, skill_group_id, proficiency) VALUES (1, 2, 2);
INSERT INTO cs_agent_skill (agent_id, skill_group_id, proficiency) VALUES (2, 1, 5);
INSERT INTO cs_agent_skill (agent_id, skill_group_id, proficiency) VALUES (2, 3, 4);
INSERT INTO cs_agent_skill (agent_id, skill_group_id, proficiency) VALUES (3, 1, 5);
INSERT INTO cs_agent_skill (agent_id, skill_group_id, proficiency) VALUES (3, 2, 5);
INSERT INTO cs_agent_skill (agent_id, skill_group_id, proficiency) VALUES (3, 3, 5);

INSERT INTO cs_customer (customer_uid, name, vip_level) VALUES ('C001', 'Normal Customer', 'NORMAL');
INSERT INTO cs_customer (customer_uid, name, vip_level) VALUES ('C002', 'VIP Customer', 'DIAMOND');
