package com.cs.alloc.controller;

import com.cs.alloc.domain.QueueSnapshot;
import com.cs.alloc.domain.SlaRiskScore;
import com.cs.alloc.service.QueueReorderService;
import com.cs.alloc.service.QueueSnapshotService;
import com.cs.alloc.service.SlaRiskCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/sla")
@RequiredArgsConstructor
public class SlaController {
    private final SlaRiskCalculator slaRiskCalculator;
    private final QueueReorderService queueReorderService;
    private final QueueSnapshotService snapshotService;

    @PostMapping("/pin/{sessionId}")
    public ResponseEntity<Map<String, Object>> pinSession(
            @PathVariable long sessionId,
            @RequestParam(defaultValue = "SYSTEM") String operatorId) {
        queueReorderService.pinSession(sessionId, operatorId);
        return ResponseEntity.ok(Map.of("success", true, "message", "会话已置顶"));
    }

    @DeleteMapping("/pin/{sessionId}")
    public ResponseEntity<Map<String, Object>> unpinSession(
            @PathVariable long sessionId,
            @RequestParam(defaultValue = "SYSTEM") String operatorId) {
        queueReorderService.unpinSession(sessionId, operatorId);
        return ResponseEntity.ok(Map.of("success", true, "message", "已取消置顶"));
    }

    @GetMapping("/risk/{skillGroupId}")
    public ResponseEntity<Map<Long, SlaRiskScore>> getRiskScores(@PathVariable long skillGroupId) {
        return ResponseEntity.ok(slaRiskCalculator.calculateSkillGroupRisks(skillGroupId));
    }

    @GetMapping("/snapshot/{skillGroupId}")
    public ResponseEntity<Optional<QueueSnapshot>> getSnapshot(@PathVariable long skillGroupId) {
        return ResponseEntity.ok(snapshotService.getSnapshot(skillGroupId));
    }

    @PostMapping("/vip-jump/{sessionId}")
    public ResponseEntity<Map<String, Object>> applyVipJump(
            @PathVariable long sessionId,
            @RequestParam int vipLevel,
            @RequestParam(defaultValue = "SYSTEM") String operatorId) {
        queueReorderService.applyVipJump(sessionId, vipLevel, operatorId);
        return ResponseEntity.ok(Map.of("success", true, "message", "VIP插队成功"));
    }
}
