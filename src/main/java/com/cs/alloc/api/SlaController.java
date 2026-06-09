package com.cs.alloc.api;

import com.cs.alloc.common.ApiResponse;
import com.cs.alloc.domain.QueueEntry;
import com.cs.alloc.domain.RequeueAuditLog;
import com.cs.alloc.service.DynamicRequeueService;
import com.cs.alloc.service.SlaRiskPredictor;
import com.cs.alloc.service.RedisService;
import com.cs.alloc.mapper.QueueEntryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sla")
@RequiredArgsConstructor
public class SlaController {
    private final SlaRiskPredictor slaRiskPredictor;
    private final DynamicRequeueService dynamicRequeueService;
    private final QueueEntryMapper queueEntryMapper;
    private final RedisService redisService;

    /**
     * 获取指定会话的 SLA 风险信息。
     */
    @GetMapping("/risk")
    public ApiResponse<Map<String, Object>> getRisk(@RequestParam long sessionId) {
        QueueEntry entry = queueEntryMapper.selectBySessionId(sessionId);
        if (entry == null) {
            return ApiResponse.fail("会话不在排队中");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("riskScore", entry.getRiskScore());
        result.put("riskLevel", entry.getRiskLevel());
        result.put("slaDeadline", entry.getSlaDeadline() != null ? entry.getSlaDeadline().toString() : null);
        result.put("pinned", Boolean.TRUE.equals(entry.getPinned()));
        result.put("position", entry.getPosition());
        return ApiResponse.ok(result);
    }

    /**
     * 获取指定技能组的队列风险概览。
     */
    @GetMapping("/risk/group")
    public ApiResponse<Map<String, Object>> getGroupRisk(@RequestParam long skillGroupId) {
        List<QueueEntry> entries = queueEntryMapper.selectBySkillGroupId(skillGroupId);
        Map<String, Long> riskDistribution = entries.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getRiskLevel() != null ? e.getRiskLevel() : "LOW",
                        Collectors.counting()
                ));
        int avgRisk = entries.isEmpty() ? 0 :
                (int) entries.stream().mapToInt(e -> e.getRiskScore() != null ? e.getRiskScore() : 0).average().orElse(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skillGroupId", skillGroupId);
        result.put("totalWaiting", entries.size());
        result.put("averageRiskScore", avgRisk);
        result.put("riskDistribution", riskDistribution);
        result.put("entries", entries.stream().map(this::toEntryMap).collect(Collectors.toList()));
        return ApiResponse.ok(result);
    }

    /**
     * 人工置顶。
     */
    @PostMapping("/pin")
    public ApiResponse<Void> pinTop(@RequestParam long sessionId, @RequestParam String operatorId) {
        dynamicRequeueService.pinTop(sessionId, operatorId);
        return ApiResponse.ok();
    }

    /**
     * 取消置顶。
     */
    @PostMapping("/unpin")
    public ApiResponse<Void> unpinTop(@RequestParam long sessionId, @RequestParam String operatorId) {
        dynamicRequeueService.unpinTop(sessionId, operatorId);
        return ApiResponse.ok();
    }

    /**
     * 手动触发 VIP 插队。
     */
    @PostMapping("/vip-jump")
    public ApiResponse<Void> vipJump(@RequestParam long sessionId, @RequestParam int vipLevel) {
        dynamicRequeueService.vipJump(sessionId, vipLevel);
        return ApiResponse.ok();
    }

    /**
     * 手动触发技能组降级兜底。
     */
    @PostMapping("/skill-fallback")
    public ApiResponse<Map<String, Object>> skillFallback(@RequestParam long sessionId) {
        boolean done = dynamicRequeueService.skillFallback(sessionId);
        return ApiResponse.ok(Map.of("sessionId", sessionId, "fallbackTriggered", done));
    }

    /**
     * 手动触发队列重排。
     */
    @PostMapping("/reorder")
    public ApiResponse<Void> reorder(@RequestParam long skillGroupId) {
        dynamicRequeueService.reorderByRisk(skillGroupId);
        return ApiResponse.ok();
    }

    /**
     * 手动触发风险扫描。
     */
    @PostMapping("/scan")
    public ApiResponse<Void> triggerScan() {
        slaRiskPredictor.scanRisk();
        return ApiResponse.ok();
    }

    /**
     * 获取队列快照。
     */
    @GetMapping("/snapshot")
    public ApiResponse<String> getSnapshot(@RequestParam long skillGroupId) {
        String snapshot = redisService.getQueueSnapshot(skillGroupId);
        return ApiResponse.ok(snapshot);
    }

    /**
     * 查询重排审计日志。
     */
    @GetMapping("/audit")
    public ApiResponse<List<RequeueAuditLog>> getAuditLogs(
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        List<RequeueAuditLog> logs = dynamicRequeueService.getAuditLogs(sessionId, action, page, pageSize);
        return ApiResponse.ok(logs);
    }

    private Map<String, Object> toEntryMap(QueueEntry qe) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sessionId", qe.getSessionId());
        map.put("customerId", qe.getCustomerId());
        map.put("position", qe.getPosition());
        map.put("priorityScore", qe.getPriorityScore());
        map.put("riskScore", qe.getRiskScore());
        map.put("riskLevel", qe.getRiskLevel());
        map.put("pinned", Boolean.TRUE.equals(qe.getPinned()));
        map.put("slaDeadline", qe.getSlaDeadline() != null ? qe.getSlaDeadline().toString() : null);
        map.put("joinedAt", qe.getJoinedAt() != null ? qe.getJoinedAt().toString() : null);
        return map;
    }
}
