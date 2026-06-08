package com.cs.alloc.api;

import com.cs.alloc.common.ApiResponse;
import com.cs.alloc.domain.Session;
import com.cs.alloc.service.QueueService;
import com.cs.alloc.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueController {
    private final QueueService queueService;
    private final SessionService sessionService;

    @PostMapping("/join")
    public ApiResponse<Map<String, Object>> joinQueue(@RequestParam long customerId, @RequestParam long skillGroupId) {
        Session session = sessionService.createSession(customerId, skillGroupId);
        int position = queueService.getPosition(session.getId());
        int waitCount = queueService.getWaitCount(skillGroupId);
        return ApiResponse.ok(Map.of("sessionId", session.getId(), "sessionNo", session.getSessionNo(), "position", position, "waitCount", waitCount, "status", session.getStatus()));
    }

    @GetMapping("/position")
    public ApiResponse<Map<String, Object>> getPosition(@RequestParam long sessionId) {
        int position = queueService.getPosition(sessionId);
        Session session = sessionService.getSession(sessionId);
        return ApiResponse.ok(Map.of("sessionId", sessionId, "position", position, "status", session.getStatus()));
    }

    @PostMapping("/cancel")
    public ApiResponse<Void> cancelQueue(@RequestParam long sessionId) {
        sessionService.closeSession(sessionId, "CUSTOMER", "CUSTOMER", "客户取消排队");
        return ApiResponse.ok();
    }

    @GetMapping("/wait-count")
    public ApiResponse<Map<String, Object>> getWaitCount(@RequestParam long skillGroupId) {
        return ApiResponse.ok(Map.of("skillGroupId", skillGroupId, "waitCount", queueService.getWaitCount(skillGroupId)));
    }
}
