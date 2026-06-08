# Customer Service Session Allocation System

智能客服会话分配后端系统，基于 Spring Boot + MyBatis + MySQL + Redis + WebSocket 构建。

## 技术栈

| 组件 | 技术 | 用途 |
|------|------|------|
| Web 框架 | Spring Boot 2.7 | REST API + WebSocket |
| ORM | MyBatis | 数据访问层 |
| 数据库 | MySQL 8.0 | 持久化存储 |
| 缓存 | Redis | 消息去重、序列号、心跳、客服状态 |
| 实时通信 | WebSocket (native) | 排队推送、消息推送、状态通知 |
| 消息队列 | 可插拔接口（默认内存实现） | 事件解耦 |

## 快速启动

### 1. 环境准备

- JDK 11+
- Maven 3.6+
- MySQL 8.0
- Redis 6.0+

### 2. 创建数据库

```bash
mysql -u root -p < src/main/resources/schema.sql
```

### 3. 修改配置

编辑 `src/main/resources/application.yml`，配置数据库和 Redis 连接信息。

### 4. 编译运行

```bash
mvn clean package -DskipTests
java -jar target/customer-service-1.0.0.jar
```

### 5. 运行测试

```bash
mvn test
```

---

## 数据库表结构

```
cs_skill_group      -- 技能组（general/technical/billing/complaint）
cs_agent            -- 客服人员（状态/最大接待量/当前负载/是否主管）
cs_agent_skill      -- 客服-技能组关联（含熟练度）
cs_customer         -- 客户（外部ID/VIP等级）
cs_session          -- 会话（状态流转/排队时间/分配时间/关闭原因）
cs_message          -- 消息记录（去重UID/序列号/发送状态）
cs_queue_entry      -- 排队池（优先级分数/入队时间）
cs_allocation_log   -- 分配日志（分配/转接/接管/重分配）
cs_audit_log        -- 操作审计（操作人/动作/目标/详情/IP）
```

### 会话状态流转

```
QUEUING → ACTIVE → CLOSED
  ↑         ↓ ↑
  |    SUSPENDED
  |         ↓
  ← TRANSFERRING → ACTIVE (new agent)
```

---

## REST API 接口

### 会话管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/session/create` | 客户发起会话，进入排队或直接分配 |
| GET  | `/api/session/{id}` | 获取会话详情 |
| GET  | `/api/session/{id}/messages` | 获取消息记录（支持 `?afterSeq=N` 增量拉取） |
| POST | `/api/session/message` | 发送消息（REST 降级通道） |
| POST | `/api/session/transfer` | 转接会话（指定客服或技能组） |
| POST | `/api/session/{id}/suspend` | 挂起会话 |
| POST | `/api/session/{id}/resume` | 恢复会话 |
| POST | `/api/session/{id}/close` | 结束会话 |
| POST | `/api/session/{id}/takeover` | 主管强制接管 |
| GET  | `/api/session/{id}/allocation-history` | 分配历史 |
| GET  | `/api/session/queue/status` | 排队池状态 |
| GET  | `/api/session/queue/position/{sessionId}` | 查询排队位置 |

### 客服管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/agent/{id}` | 获取客服信息 |
| POST | `/api/agent/status` | 变更在线状态 |
| GET  | `/api/agent/{id}/sessions` | 获取当前会话列表 |
| PUT  | `/api/agent/{id}/max-concurrent` | 调整最大接待量 |
| GET  | `/api/agent/online` | 在线客服列表 |

### 请求示例

**创建会话：**
```json
POST /api/session/create
{
  "customerUid": "C001",
  "customerName": "张三",
  "skillGroup": "technical",
  "metadata": "{\"source\": \"web\"}"
}
```

**转接会话：**
```json
POST /api/session/transfer
{
  "sessionId": 1,
  "targetAgentId": 2,
  "reason": "需要技术支持"
}
```

**变更客服状态：**
```json
POST /api/agent/status
{
  "agentId": 1,
  "status": "ONLINE"
}
```

---

## WebSocket 事件设计

### 连接方式

```
客户端: ws://host:8080/ws/chat?role=customer&uid=CUSTOMER_UID
客服端: ws://host:8080/ws/chat?role=agent&agentId=123
```

### 客户端 → 服务端（Inbound）

| 事件 | 说明 | 数据结构 |
|------|------|----------|
| `MESSAGE` | 发送消息 | `{ event: "MESSAGE", sessionId: 1, data: { content, contentType, messageUid } }` |
| `HEARTBEAT` | 心跳 | `{ event: "HEARTBEAT" }` |
| `MSG_READ` | 已读回执 | `{ event: "MSG_READ", sessionId: 1, data: { sequenceNo: 5 } }` |

### 服务端 → 客户端（Outbound）

| 事件 | 触发时机 | 推送对象 |
|------|----------|----------|
| `QUEUE_POSITION` | 定时广播（5s间隔） | 排队中的客户 |
| `MESSAGE` | 新消息 | 会话双方 |
| `SESSION_ASSIGNED` | 会话被分配 | 客户 + 客服 |
| `SESSION_TRANSFERRED` | 会话转接 | 客户 + 原客服 + 新客服 |
| `SESSION_CLOSED` | 会话关闭 | 客户 + 客服 |
| `AGENT_STATUS` | 客服状态变化 | 所有在线客服 |
| `SYSTEM_NOTICE` | 系统通知 | 目标用户 |
| `ERROR` | 错误 | 触发方 |
| `HEARTBEAT` | 心跳回应 | 发送方 |

### 事件数据格式

所有事件统一使用 `WsEvent` 结构：
```json
{
  "event": "MESSAGE",
  "sessionId": 1,
  "data": { ... },
  "timestamp": 1700000000000
}
```

---

## 分配算法

### 核心策略

```
优先级分数 = VIP权重 + 等待时间老化加分

VIP权重: NORMAL=0, SILVER=10, GOLD=20, DIAMOND=30
老化加分: 每等待30秒 +1分
```

### 分配流程

```
1. 客户发起咨询 → 创建会话(QUEUING)
2. 入排队池 → 计算优先级分数
3. 按优先级排序 → 高分优先服务
4. 查找可用客服:
   a. 按技能组筛选
   b. 筛选 ONLINE 且有剩余容量的客服
   c. 按剩余容量降序排序（负载均衡）
   d. 选择容量最多的客服
5. 分配成功 → 出队 → 通知双方
6. 无可用客服 → 留在队列 → 定时器重试（10s）
```

### 转接策略

- **指定转接**：直接转给目标客服（需有容量）
- **技能组转接**：在目标技能组中找最空闲客服
- **主管接管**：强制从当前客服接管（仅主管权限）

### 重分配触发条件

- 客服下线（心跳丢失后宽限期过期）
- 客服最大接待量调整
- 定时器轮询排队池

---

## 异常处理设计

### 客服断线重连

```
1. WebSocket 断开 → 设置 Redis 键 (TTL=30s)
2. 30s 内重连 → 恢复状态，保持所有会话
3. 30s 未重连 → 标记 OFFLINE → 活跃会话重新入队
4. 重新入队的会话保留消息历史 → 新客服可查看完整上下文
```

### 消息去重

```
1. 客户端生成 messageUid（UUID）
2. 服务端用 Redis SETNX 检查:
   - 新消息 → 入库 + 推送
   - 重复消息 → 返回已有记录，不重复入库
3. 去重键 5 分钟后自动过期
```

### 转接状态一致性

```
1. 设置 TRANSFERRING 中间状态 → 防止并发操作
2. 分配新客服成功 → 更新为 ACTIVE + 新客服
3. 分配失败 → 回滚为 ACTIVE + 原客服
4. 转接全程消息不丢失（同一 session，消息连续）
```

### 客户长时间无响应

```
1. 定时任务每 60s 扫描活跃会话
2. last_active_at 超过阈值(默认5分钟) → 自动关闭
3. 发送系统消息通知双方
4. 释放客服容量
```

### 客服容量变化后重分配

```
1. 调整 max_concurrent → 发布 MQ 事件
2. 定时任务检测排队池 → 尝试重新分配
3. 客服上线 → 自动触发排队池处理
```

---

## 消息队列模块

系统使用可插拔的消息队列接口 `MessageQueue`，默认提供 `InMemoryMessageQueue` 内存实现。

### 替换为生产级 MQ

实现 `MessageQueue` 接口并注册为 Spring Bean：

```java
@Component
@Primary
public class RabbitMqAdapter implements MessageQueue {
    @Override
    public void publish(String topic, Object message) { /* ... */ }

    @Override
    public void subscribe(String topic, Consumer<Object> consumer) { /* ... */ }

    @Override
    public void unsubscribe(String topic, Consumer<Object> consumer) { /* ... */ }
}
```

### MQ Topic 清单

| Topic | 触发时机 |
|-------|----------|
| `session.created` | 会话创建 |
| `session.assigned` | 会话分配 |
| `session.transferred` | 会话转接 |
| `session.closed` | 会话关闭 |
| `message.sent` | 消息发送 |
| `agent.status.changed` | 客服状态变更 |
| `queue.updated` | 排队池变化 |

---

## 定时任务

| 任务 | 间隔 | 说明 |
|------|------|------|
| 排队处理 | 10s | 尝试将排队会话分配给空闲客服 |
| 位置广播 | 5s | 向排队客户推送当前排队位置 |
| 空闲检测 | 60s | 关闭长时间无响应的会话 |
| 心跳检测 | 15s | 检测客服断线并触发重分配 |
| 优先级刷新 | 30s | 更新排队优先级分数（等待时间老化） |

---

## 项目结构

```
src/main/java/com/customerservice/
├── Application.java                 # 启动类
├── config/
│   ├── RedisConfig.java             # Redis 序列化配置
│   └── WebSocketConfig.java         # WebSocket 端点注册
├── controller/
│   ├── SessionController.java       # 会话/消息/排队接口
│   └── AgentController.java         # 客服管理接口
├── service/
│   ├── SessionService.java          # 会话生命周期管理
│   ├── AllocationService.java       # 核心分配算法
│   ├── QueueService.java            # 排队池管理
│   ├── AgentService.java            # 客服状态管理
│   ├── MessageService.java          # 消息收发与去重
│   └── AuditService.java            # 操作审计
├── websocket/
│   ├── CustomerWebSocketHandler.java # WebSocket 消息处理
│   ├── WebSocketSessionManager.java  # 在线会话管理
│   └── WebSocketAuthInterceptor.java # 连接鉴权
├── mq/
│   ├── MessageQueue.java            # MQ 接口（可替换）
│   ├── InMemoryMessageQueue.java    # 内存 MQ 实现
│   └── MqTopics.java               # Topic 常量
├── mapper/                          # MyBatis Mapper 接口
├── model/
│   ├── entity/                      # 数据实体
│   ├── dto/                         # 请求/响应 DTO
│   └── enums/                       # 状态枚举
├── exception/
│   ├── BusinessException.java       # 业务异常
│   └── GlobalExceptionHandler.java  # 全局异常处理
└── scheduler/
    └── ServiceScheduler.java        # 定时任务
```

## 测试说明

| 测试类 | 覆盖范围 |
|--------|----------|
| `AllocationServiceTest` | 分配算法：最少负载优先、无可用客服、转接排除、指定转接 |
| `QueueServiceTest` | 优先级计算：VIP权重、等待老化、边界条件 |
| `MessageServiceTest` | 消息发送、去重检测、向已关闭会话发送 |
| `InMemoryMessageQueueTest` | MQ 发布订阅、多订阅者、取消订阅 |
