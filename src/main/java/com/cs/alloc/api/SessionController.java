package com.cs.alloc.api;

import com.cs.alloc.common.ApiResponse;
import com.cs.alloc.domain.Session;
import com.cs.alloc.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/session")
@RequiredArgsConstructor
public class SessionController {
    private final SessionService sessionService;

    @PostMapping("/accept")
    public ApiResponse<Session> accept(@RequestParam long sessionId, @RequestParam long agentId) {
        return ApiResponse.ok(sessionService.acceptSession(sessionId, agentId));
    }

    @PostMapping("/transfer")
    public ApiResponse<Session> transfer(@RequestParam long sessionId, @RequestParam long fromAgentId, @RequestParam(required = false) Long toAgentId, @RequestParam(required = false) Long toSkillGroupId, @RequestParam(required = false) String reason) {
        return ApiResponse.ok(sessionService.transferSession(sessionId, fromAgentId, toAgentId, toSkillGroupId, reason));
    }

    @PostMapping("/suspend")
    public ApiResponse<Session> suspend(@RequestParam long sessionId, @RequestParam long agentId) {
        return ApiResponse.ok(sessionService.suspendSession(sessionId, agentId));
    }

    @PostMapping("/resume")
    public ApiResponse<Session> resume(@RequestParam long sessionId, @RequestParam long agentId) {
        return ApiResponse.ok(sessionService.resumeSession(sessionId, agentId));
    }

    @PostMapping("/close")
    public ApiResponse<Void> close(@RequestParam long sessionId, @RequestParam String operatorId, @RequestParam String operatorType, @RequestParam(required = false) String reason) {
        sessionService.closeSession(sessionId, operatorId, operatorType, reason);
        return ApiResponse.ok();
    }

    @PostMapping("/takeover")
    public ApiResponse<Session> takeover(@RequestParam long sessionId, @RequestParam long supervisorId) {
        return ApiResponse.ok(sessionService.supervisorTakeover(sessionId, supervisorId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Session> getSession(@PathVariable long id) {
        return ApiResponse.ok(sessionService.getSession(id));
    }

    @GetMapping("/agent/{agentId}/active")
    public ApiResponse<List<Session>> getAgentActiveSessions(@PathVariable long agentId) {
        return ApiResponse.ok(sessionService.getAgentActiveSessions(agentId));
    }

    @GetMapping("/customer/{customerId}/active")
    public ApiResponse<List<Session>> getCustomerActiveSessions(@PathVariable long customerId) {
        return ApiResponse.ok(sessionService.getCustomerActiveSessions(customerId));
    }
}
