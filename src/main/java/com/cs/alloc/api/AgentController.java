package com.cs.alloc.api;

import com.cs.alloc.common.ApiResponse;
import com.cs.alloc.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {
    private final SessionService sessionService;

    @PostMapping("/online")
    public ApiResponse<Void> online(@RequestParam long agentId) {
        sessionService.agentOnline(agentId);
        return ApiResponse.ok();
    }

    @PostMapping("/offline")
    public ApiResponse<Void> offline(@RequestParam long agentId) {
        sessionService.agentOffline(agentId);
        return ApiResponse.ok();
    }

    @PostMapping("/break")
    public ApiResponse<Void> takeBreak(@RequestParam long agentId) {
        sessionService.agentBreak(agentId);
        return ApiResponse.ok();
    }
}
