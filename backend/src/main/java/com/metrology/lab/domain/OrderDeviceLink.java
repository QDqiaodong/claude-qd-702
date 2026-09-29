package com.metrology.lab.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 委托-标准器挂接/占用关系。
 *
 * <p>活动行（{@code releasedAt == null}）= 标准器正被该委托占用；
 * {@code releasedAt} 有值 = 已释放（清单替换 / 退回收样 / 作废 / 已发放留痕），仅作历史留痕。
 *
 * <p>并发安全不只靠应用层判断：数据库上有两道唯一索引兜底——
 * {@code uk_order_device_active(order_id, device_id, active_flag)} 防同单重复挂接，
 * {@code uk_device_active(device_id, active_flag)} 保证一台标准器全局只有一条活动行，
 * 两名检定员同时抢占时，后提交的一笔在 INSERT 阶段就会撞键失败，委托原数据不动。
 *
 * <p>{@code active_flag} 是由 {@code released_at} 派生的 STORED 生成列（MySQL 唯一索引允许多个 NULL，
 * 不能直接拿可空列做唯一键），由数据库维护，这里不映射为属性。
 */
@Data
@Entity
@Table(name = "lab_order_device")
public class OrderDeviceLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "attached_at", nullable = false)
    private LocalDateTime attachedAt;

    /** 释放时间：null 表示占用中的活动行 */
    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    /** REPLACE / ROLLBACK_RECEIVED / VOID / ISSUED */
    @Column(name = "release_reason", length = 24)
    private String releaseReason;
}
