# 客服会话分配后端 (Customer Service Session Allocation)

基于 Spring Boot 的客服会话分配系统后端, 解决旧系统三大痛点:
- **排队不透明**: 实时推送排队位置和预计等待
- **客服状态不准**: Redis 实时状态 + 心跳机制 + 断线重连
- **转接丢消息**: 消息幂等去重 + 状态机 + 分布式锁

## 技术栈

| 组件 | 用途 |
|------|------|
| Spring Boot 3.2 | 应用框架 |
| MyBatis | ORM 持久层 |
| MySQL 8 | 主数据库 |
| Redis | 实时状态、排队、分布式锁、幂等键 |
| WebSocket | 双向实时通信 |
| 可替换 MQ | InMemory / Redis Pub/Sub (可对接 Kafka/RabbitMQ) |

## 项目结构

```
src/main/java/com/cs/alloc/
├── Application.java                # 启动入口
├── config/
│   ├── AppConfig.java              # MQ Bean 配置(可切换实现)
│   ├── WebSocketConfig.java        # WS 端点注册
│   ├── ScheduleConfig.java         # 定时任务线程池
│   └── MqBridgeInitializer.java    # MQ→WS 事件桥接
├── common/
│   ├── ApiResponse.java            # 统一响应体
│   ├── BizException.java           # 业务异常
│   └── GlobalExceptionHandler.java # 全局异常处理
├── domain/                         # 9 个实体类
├── mapper/                         # 8 个 MyBatis Mapper
├── service/                        # 核心业务逻辑
│   ├── AllocationEngine.java       # ★ 分配引擎(评分+调度)
│   ├── QueueService.java           # 排队管理
│   ├── SessionService.java         # 会话生命周期
│   ├── MessageService.java         # 消息发送(幂等)
│   ├── RedisService.java           # Redis 状态管理
│   ├── AuditService.java           # 审计日志
│   ├── MessageQueue.java           # MQ 接口(可替换)
│   ├── InMemoryMessageQueue.java   # 内存实现
│   └── RedisMessageQueue.java      # Redis Pub/Sub 实现
├── api/                            # 4 个 REST Controller
└── ws/                             # WebSocket 层
    ├── CustomerWebSocketHandler.java
    ├── AgentWebSocketHandler.java
    └── WsEventPusher.java          # 事件推送+MQ桥接
```

---

## 一、数据库表结构 (9 张表)

DDL 见 `sql/schema.sql`:

| 表名 | 用途 | 关键索引 |
|------|------|----------|
| `customer` | 客户信息 | vip_level |
| `skill_group` | 技能组 | - |
| `agent` | 客服 | skill_group_id |
| `agent_state` | 客服实时状态 | PK: agent_id |
| `session` | 会话 | session_no(唯一), status |
| `message` | 消息记录 | idempotency_key(唯一) |
| `queue_entry` | 排队记录 | session_id(唯一) |
| `allocation_log` | 分配日志 | session_id |
| `audit_log` | 操作审计 | target_type+target_id |

### 会话状态机

```
WAITING ──→ ASSIGNED ──→ ACTIVE ──→ TRANSFERRING ──→ WAITING (重新入队)
   │            │           │
   │            │           └→ SUSPENDED → ACTIVE (恢复)
   │            │
   └────────────┴─────────────→ CLOSED
```

---

## 二、REST API

### 排队
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/queue/join?customerId=&skillGroupId=` | 进入排队 |
| GET | `/api/queue/position?sessionId=` | 排队位置 |
| POST | `/api/queue/cancel?sessionId=` | 取消排队 |
| GET | `/api/queue/wait-count?skillGroupId=` | 排队人数 |

### 会话
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/session/accept?sessionId=&agentId=` | 客服接入 |
| POST | `/api/session/transfer?sessionId=&fromAgentId=&toAgentId=&toSkillGroupId=` | 转接 |
| POST | `/api/session/suspend?sessionId=&agentId=` | 挂起 |
| POST | `/api/session/resume?sessionId=&agentId=` | 恢复 |
| POST | `/api/session/close?sessionId=&operatorId=&operatorType=` | 结束 |
| POST | `/api/session/takeover?sessionId=&supervisorId=` | 主管接管 |
| GET | `/api/session/{id}` | 查询会话 |
| GET | `/api/session/agent/{agentId}/active` | 客服活跃会话 |

### 消息
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/message/send` | 发送消息(支持幂等键) |
| GET | `/api/message/history?sessionId=&page=&pageSize=` | 历史消息 |

### 客服
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/online?agentId=` | 上线 |
| POST | `/api/agent/offline?agentId=` | 离线 |
| POST | `/api/agent/break?agentId=` | 小休 |

---

## 三、WebSocket 事件

### 连接
| 端 | 地址 | 参数 |
|----|------|------|
| 客户 | `ws://host/ws/customer` | `?customerId=xxx` |
| 客服 | `ws://host/ws/agent` | `?agentId=xxx` |

### 统一消息格式
```json
{"event": "事件名", "data": {...}, "timestamp": 1717862400000}
```

### 客户端事件 (双向)
| 方向 | 事件 | 说明 |
|------|------|------|
| C→S | `send_message` | 发送聊天(含 idempotencyKey) |
| C→S | `cancel_queue` | 取消排队 |
| C→S | `ping` | 心跳 |
| S→C | `queue.updated` | 排队位置更新 |
| S→C | `session.assigned` | 会话已分配 |
| S→C | `session.closed` | 会话关闭 |
| S→C | `chat.message` | 收到新消息 |

### 客服端事件 (双向)
| 方向 | 事件 | 说明 |
|------|------|------|
| A→S | `send_message` / `accept_session` / `transfer_session` / `suspend_session` / `resume_session` / `close_session` / `heartbeat` | 操作指令 |
| S→A | `connected`(含重连会话列表) / `session.assigned` / `session.new_transfer` / `session.transferred` / `chat.message` / `agent.status_changed` / `system.notice` | 推送通知 |

---

## 四、分配算法

### 调度机制
每 2 秒执行一轮: 遍历排队池 → 按技能组分组 → 为每个客户匹配评分最高客服

### 客服评分公式
```
AgentScore = skillMatch(50) - currentLoad×10 + idleBonus(20) + remainingCapacity×2
```
- 空闲匹配: **80分** | 负载3匹配: **24分** | 空闲不匹配: **30分**

### 客户排队优先级
```
PriorityScore = vipLevel × 10 + waitSeconds
```

### 超载重分配
每 30 秒检查, 超载客服最新会话退回排队池

---

## 五、异常场景处理

| 场景 | 处理方式 |
|------|----------|
| **客服断线重连** | 30秒宽限期, 重连恢复会话列表, 超时则离线处理 |
| **重复发送消息** | Redis SETNX 幂等键(TTL 10min) + DB 唯一索引双重保护 |
| **转接状态不一致** | Redis 分布式锁保证原子性, 10秒自动释放 |
| **客户长时间无响应** | 可配置 idle-timeout-seconds(默认300秒) |
| **容量变化重分配** | 定时检查 + 离线自动退回排队 |
| **并发分配冲突** | 分配引擎和客服接入共用分布式锁 |

---

## 六、消息队列模块

```java
public interface MessageQueue {
    void publish(String topic, String message);
    void subscribe(String topic, Consumer<String> consumer);
}
```

| 实现 | 场景 | 切换方式 |
|------|------|----------|
| `InMemoryMessageQueue` | 单机/测试 | `cs.mq.type=memory` |
| `RedisMessageQueue` | 多实例 | `cs.mq.type=redis` |
| 自定义(如RabbitMQ) | 生产环境 | 实现接口 + 注册Bean |

7 个 Topic: `session.allocated`, `session.transferred`, `session.closed`, `queue.updated`, `agent.status`, `chat.message`, `system.notice`

---

## 七、运行说明

### 前置: JDK 17+, MySQL 8+, Redis 6+, Maven 3.8+

```bash
# 1. 建库建表
mysql -u root -p -e "CREATE DATABASE cs_alloc DEFAULT CHARSET utf8mb4;"
mysql -u root -p cs_alloc < sql/schema.sql

# 2. 修改 src/main/resources/application.yml 中的数据库和Redis连接

# 3. 编译运行
mvn clean package -DskipTests
java -jar target/alloc-1.0.0-SNAPSHOT.jar

# 4. 运行测试 (不依赖MySQL/Redis)
mvn test
```

### 快速验证
```bash
curl -X POST "http://localhost:8080/api/agent/online?agentId=1"
curl -X POST "http://localhost:8080/api/queue/join?customerId=1&skillGroupId=1"
curl "http://localhost:8080/api/queue/position?sessionId=1"
# 等待2秒自动分配后:
curl "http://localhost:8080/api/session/1"
curl -X POST http://localhost:8080/api/message/send \
  -H "Content-Type: application/json" \
  -d '{"sessionId":1,"senderId":"1","senderType":"CUSTOMER","content":"你好","idempotencyKey":"msg-001"}'
```

### WebSocket (wscat)
```bash
wscat -c "ws://localhost:8080/ws/customer?customerId=1"
wscat -c "ws://localhost:8080/ws/agent?agentId=1"
> {"event":"heartbeat"}
> {"event":"send_message","sessionId":1,"content":"您好"}
```

---

## 八、测试结果

```
Tests run: 26, Failures: 0, Errors: 0, Skipped: 0 - BUILD SUCCESS
```

| 测试类 | 用例数 | 覆盖 |
|--------|--------|------|
| AllocationEngineTest | 6 | 评分算法、客服选择、VIP优先级、等待时间 |
| SessionServiceTest | 10 | 创建/接入/转接/挂起/恢复/关闭/接管/离线回退 |
| MessageServiceTest | 4 | 正常发送/幂等去重/已关闭拒绝/不存在拒绝 |
| QueueServiceTest | 6 | 入队/幂等/离队/位置/重新入队 |
