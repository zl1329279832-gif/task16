package com.customerservice.service;

import com.customerservice.mapper.AuditLogMapper;
import com.customerservice.model.entity.AuditLog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuditService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @Async
    public void log(String operatorType, Long operatorId, String action,
                    String targetType, Long targetId, Object detail) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setOperatorType(operatorType);
            auditLog.setOperatorId(operatorId);
            auditLog.setAction(action);
            auditLog.setTargetType(targetType);
            auditLog.setTargetId(targetId);
            if (detail != null) {
                auditLog.setDetail(objectMapper.writeValueAsString(detail));
            }
            auditLogMapper.insert(auditLog);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize audit detail", e);
        } catch (Exception e) {
            log.error("Failed to write audit log", e);
        }
    }

    public void log(String operatorType, Long operatorId, String action,
                    String targetType, Long targetId) {
        log(operatorType, operatorId, action, targetType, targetId, null);
    }
}
