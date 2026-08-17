package com.cs.infra.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * PG 表 {@code cs_user}：演示/业务用户，供前端切换身份与查历史会话。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "cs_user")
public class CsUserEntity {

    @Id
    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "username", length = 128, nullable = false)
    private String username;

    @Column(name = "nickname", length = 128)
    private String nickname;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "email", length = 128)
    private String email;

    @Column(name = "vip_level")
    private Short vipLevel;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
