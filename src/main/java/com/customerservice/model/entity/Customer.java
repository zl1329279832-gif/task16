package com.customerservice.model.entity;

import com.customerservice.model.enums.VipLevel;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Customer {
    private Long id;
    private String customerUid;
    private String name;
    private VipLevel vipLevel;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
