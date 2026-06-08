package com.cs.alloc.service;

import com.cs.alloc.domain.AuditLog;
import com.cs.alloc.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditLogMapper auditLogMapper;

    public void record(String operatorId, String operatorType, String action, String targetType, String targetId, String detail, String ipAddress) {
        AuditLog auditLog = new AuditLog();
        auditLog.setOperatorId(operatorId); auditLog.setOperatorType(operatorType); auditLog.setAction(action);
        auditLog.setTargetType(targetType); auditLog.setTargetId(targetId); auditLog.setDetail(detail); auditLog.setIpAddress(ipAddress);
        auditLogMapper.insert(auditLog);
        log.info("审计: [{}:{}] {} {}:{}", operatorType, operatorId, action, targetType, targetId);
    }

    public List<AuditLog> queryByTarget(String targetType, String targetId, int page, int pageSize) {
        return auditLogMapper.selectByTarget(targetType, targetId, (page - 1) * pageSize, pageSize);
    }
}
