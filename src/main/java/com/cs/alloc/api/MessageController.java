package com.cs.alloc.api;

import com.cs.alloc.common.ApiResponse;
import com.cs.alloc.domain.Message;
import com.cs.alloc.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/message")
@RequiredArgsConstructor
public class MessageController {
    private final MessageService messageService;

    @PostMapping("/send")
    public ApiResponse<Message> send(@RequestBody Map<String, Object> body) {
        long sessionId = Long.parseLong(String.valueOf(body.get("sessionId")));
        String senderId = String.valueOf(body.get("senderId"));
        String senderType = String.valueOf(body.get("senderType"));
        String content = String.valueOf(body.get("content"));
        String msgType = body.containsKey("msgType") ? String.valueOf(body.get("msgType")) : "TEXT";
        String idempotencyKey = body.containsKey("idempotencyKey") ? String.valueOf(body.get("idempotencyKey")) : null;
        return ApiResponse.ok(messageService.sendMessage(sessionId, senderId, senderType, content, msgType, idempotencyKey));
    }

    @GetMapping("/history")
    public ApiResponse<Map<String, Object>> history(@RequestParam long sessionId, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int pageSize) {
        List<Message> messages = messageService.getHistory(sessionId, page, pageSize);
        long total = messageService.getMessageCount(sessionId);
        return ApiResponse.ok(Map.of("messages", messages, "total", total, "page", page, "pageSize", pageSize));
    }
}
