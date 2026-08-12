package com.cs.common.model;

import com.cs.common.enums.PermissionMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@link com.cs.tools.permission.PermissionGate} 裁决结果载体（mode + 原因/阈值等）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionDecision {

    private PermissionMode mode;
    private String reason;
    private String permission;

    public static PermissionDecision auto(String permission, String reason) {
        return PermissionDecision.builder()
                .mode(PermissionMode.AUTO)
                .permission(permission)
                .reason(reason)
                .build();
    }

    public static PermissionDecision confirm(String permission, String reason) {
        return PermissionDecision.builder()
                .mode(PermissionMode.CONFIRM)
                .permission(permission)
                .reason(reason)
                .build();
    }

    public static PermissionDecision deny(String permission, String reason) {
        return PermissionDecision.builder()
                .mode(PermissionMode.DENY)
                .permission(permission)
                .reason(reason)
                .build();
    }
}
