package com.customerservice.controller;

import com.customerservice.model.dto.AgentStatusRequest;
import com.customerservice.model.dto.ApiResponse;
import com.customerservice.model.entity.Agent;
import com.customerservice.model.entity.ChatSession;
import com.customerservice.service.AgentService;
import com.customerservice.service.SessionService;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;
    private final SessionService sessionService;

    public AgentController(AgentService agentService, SessionService sessionService) {
        this.agentService = agentService;
        this.sessionService = sessionService;
    }

    /**
     * GET /api/agent/{id}
     * Get agent details.
     */
    @GetMapping("/{id}")
    public ApiResponse<Agent> getAgent(@PathVariable Long id) {
        return ApiResponse.ok(agentService.getAgent(id));
    }

    /**
     * POST /api/agent/status
     * Change agent online status.
     */
    @PostMapping("/status")
    public ApiResponse<Agent> changeStatus(@Valid @RequestBody AgentStatusRequest req) {
        return ApiResponse.ok(agentService.changeStatus(req.getAgentId(), req.getStatus()));
    }

    /**
     * GET /api/agent/{id}/sessions
     * Get agent's active sessions.
     */
    @GetMapping("/{id}/sessions")
    public ApiResponse<List<ChatSession>> getAgentSessions(@PathVariable Long id) {
        return ApiResponse.ok(sessionService.getAgentSessions(id));
    }

    /**
     * PUT /api/agent/{id}/max-concurrent
     * Update agent's max concurrent sessions.
     */
    @PutMapping("/{id}/max-concurrent")
    public ApiResponse<?> updateMaxConcurrent(@PathVariable Long id,
                                              @RequestParam int maxConcurrent) {
        agentService.updateMaxConcurrent(id, maxConcurrent);
        return ApiResponse.ok();
    }

    /**
     * GET /api/agent/online
     * Get all online agents.
     */
    @GetMapping("/online")
    public ApiResponse<List<Agent>> getOnlineAgents() {
        return ApiResponse.ok(agentService.getOnlineAgents());
    }
}
