package com.customerservice.controller;

import com.customerservice.model.dto.*;
import com.customerservice.model.entity.ChatMessage;
import com.customerservice.model.entity.ChatSession;
import com.customerservice.model.enums.CloseReason;
import com.customerservice.service.AllocationService;
import com.customerservice.service.MessageService;
import com.customerservice.service.QueueService;
import com.customerservice.service.SessionService;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final SessionService sessionService;
    private final MessageService messageService;
    private final QueueService queueService;
    private final AllocationService allocationService;

    public SessionController(SessionService sessionService,
                             MessageService messageService,
                             QueueService queueService,
                             AllocationService allocationService) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.queueService = queueService;
        this.allocationService = allocationService;
    }

    /**
     * POST /api/session/create
     * Customer initiates a new service session.
     */
    @PostMapping("/create")
    public ApiResponse<ChatSession> createSession(@Valid @RequestBody SessionRequest req) {
        return ApiResponse.ok(sessionService.createSession(req));
    }

    /**
     * GET /api/session/{id}
     * Get session details.
     */
    @GetMapping("/{id}")
    public ApiResponse<ChatSession> getSession(@PathVariable Long id) {
        return ApiResponse.ok(sessionService.getSession(id));
    }

    /**
     * GET /api/session/{id}/messages
     * Get all messages for a session.
     */
    @GetMapping("/{id}/messages")
    public ApiResponse<List<ChatMessage>> getMessages(
            @PathVariable Long id,
            @RequestParam(required = false) Long afterSeq) {
        if (afterSeq != null) {
            return ApiResponse.ok(messageService.getMessagesAfterSeq(id, afterSeq));
        }
        return ApiResponse.ok(messageService.getSessionMessages(id));
    }

    /**
     * POST /api/session/message
     * Send a message (REST fallback; prefer WebSocket).
     */
    @PostMapping("/message")
    public ApiResponse<ChatMessage> sendMessage(@Valid @RequestBody MessageRequest req) {
        return ApiResponse.ok(messageService.sendMessage(req));
    }

    /**
     * POST /api/session/transfer
     * Transfer session to another agent or skill group.
     */
    @PostMapping("/transfer")
    public ApiResponse<ChatSession> transferSession(@Valid @RequestBody TransferRequest req) {
        return ApiResponse.ok(sessionService.transferSession(req));
    }

    /**
     * POST /api/session/{id}/suspend
     * Suspend (hold) a session.
     */
    @PostMapping("/{id}/suspend")
    public ApiResponse<ChatSession> suspendSession(@PathVariable Long id,
                                                   @RequestParam Long agentId) {
        return ApiResponse.ok(sessionService.suspendSession(id, agentId));
    }

    /**
     * POST /api/session/{id}/resume
     * Resume a suspended session.
     */
    @PostMapping("/{id}/resume")
    public ApiResponse<ChatSession> resumeSession(@PathVariable Long id,
                                                  @RequestParam Long agentId) {
        return ApiResponse.ok(sessionService.resumeSession(id, agentId));
    }

    /**
     * POST /api/session/{id}/close
     * Close a session.
     */
    @PostMapping("/{id}/close")
    public ApiResponse<ChatSession> closeSession(@PathVariable Long id,
                                                 @RequestParam(defaultValue = "NORMAL") String reason,
                                                 @RequestParam(required = false) Long operatorId,
                                                 @RequestParam(defaultValue = "AGENT") String operatorType) {
        return ApiResponse.ok(sessionService.closeSession(
                id, operatorId, operatorType, CloseReason.valueOf(reason)));
    }

    /**
     * POST /api/session/{id}/takeover
     * Supervisor force-takeover.
     */
    @PostMapping("/{id}/takeover")
    public ApiResponse<ChatSession> takeoverSession(@PathVariable Long id,
                                                    @RequestParam Long supervisorId) {
        return ApiResponse.ok(sessionService.takeoverSession(id, supervisorId));
    }

    /**
     * GET /api/session/{id}/allocation-history
     * Get allocation history for a session.
     */
    @GetMapping("/{id}/allocation-history")
    public ApiResponse<?> getAllocationHistory(@PathVariable Long id) {
        return ApiResponse.ok(allocationService.getSessionAllocationHistory(id));
    }

    /**
     * GET /api/session/queue/status
     * Get current queue status.
     */
    @GetMapping("/queue/status")
    public ApiResponse<Map<String, Object>> getQueueStatus() {
        return ApiResponse.ok(Map.of(
                "queueSize", queueService.getQueueSize(),
                "queue", queueService.getOrderedQueue(null)
        ));
    }

    /**
     * GET /api/session/queue/position/{sessionId}
     * Get queue position for a specific session.
     */
    @GetMapping("/queue/position/{sessionId}")
    public ApiResponse<Map<String, Object>> getQueuePosition(@PathVariable Long sessionId) {
        int position = queueService.getPosition(sessionId);
        return ApiResponse.ok(Map.of(
                "position", position,
                "estimatedWaitSeconds", Math.max(0, position) * 120
        ));
    }
}
