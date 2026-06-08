package com.customerservice.model.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;

@Data
public class SessionRequest {
    @NotBlank(message = "Customer UID is required")
    private String customerUid;
    private String customerName;
    private String skillGroup;
    private String metadata;
}
