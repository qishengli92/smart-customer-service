package com.cs.common.enums;

/**
 * 写工具权限三态：AUTO（直接执行）/ CONFIRM（HITL）/ DENY（拒绝）。
 * <p>
 * 仅由 PermissionGate 产出，领域 Agent 不得自行解释。
 */
public enum PermissionMode {
    AUTO,
    CONFIRM,
    DENY
}
