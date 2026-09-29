package com.metrology.lab.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出参视图。
 *
 * <p>全部用 record：只在 service 里构造、直接序列化给前端，不需要 setter。
 * 派生字段（到期天数、流转序号、证书剩余天数）一律在 service 里算好，前端只负责着色展示。
 */
public final class Views {

    private Views() {
    }

    /** 模块一：送检客户 */
    public record ClientView(
            Long id,
            String code,
            String name,
            String creditCode,
            String contactName,
            String phone,
            String address,
            String settleType,
            String settleTypeName,
            Integer settleDays,
            String remark,
            LocalDate createdAt,
            Integer instrumentCount,
            Long orderCount) {
    }

    /** 客户抽屉里的历史委托单 */
    public record ClientOrderView(
            Long id,
            String orderNo,
            String instNo,
            String instrumentName,
            String procNo,
            LocalDate receiveDate,
            String status,
            String statusName,
            String conclusion,
            String conclusionName,
            String certNo,
            BigDecimal fee) {
    }

    /** 模块二：计量器具台账 */
    public record InstrumentView(
            Long id,
            String instNo,
            String name,
            String modelSpec,
            String measureRange,
            String accuracyGrade,
            String manufacturer,
            String factoryNo,
            Integer cycleMonths,
            LocalDate lastCalDate,
            LocalDate nextDueDate,
            String status,
            String statusName,
            Long clientId,
            String clientName,
            /** 距下次到期日的天数，null 表示未排期 */
            Integer dueDays,
            /** true = 已超期（dueDays < 0），需立即停用补检 */
            boolean overdue,
            /** true = 30 天内到期，台账表格标红 */
            boolean dueWarn,
            String remark,
            LocalDateTime updatedAt) {
    }

    /** 模块三：检定规程 */
    public record ProcedureView(
            Long id,
            String procNo,
            String name,
            String category,
            String standardNo,
            String deviceReq,
            String envCondition,
            BigDecimal unitFee,
            String remark,
            Long orderCount) {
    }

    /** 模块四：检定委托与流转单 */
    public record OrderView(
            Long id,
            String orderNo,
            Long clientId,
            String clientName,
            Long instrumentId,
            String instNo,
            String instrumentName,
            String modelSpec,
            String measureRange,
            String accuracyGrade,
            String manufacturer,
            String factoryNo,
            Long procedureId,
            String procNo,
            String procedureName,
            BigDecimal unitFee,
            LocalDate receiveDate,
            String verifier,
            String checker,
            String measuredData,
            String conclusion,
            String conclusionName,
            String certNo,
            String status,
            String statusName,
            /** 流转序号：0 收样 … 4 已发放；作废为 -1 */
            Integer stepIndex,
            LocalDate certValidUntil,
            BigDecimal fee,
            String remark,
            LocalDateTime updatedAt,
            /** 当前占用中的标准器挂接项 */
            List<DeviceLinkView> devices,
            /** 已释放的历史挂接项（替换 / 退回收样 / 作废 / 发放留痕） */
            List<DeviceLinkView> releasedDevices,
            /** 当前占用标准器数量 */
            int deviceCount,
            /** 占用标准器中最早的证书到期日 */
            LocalDate earliestCertValidUntil,
            /** true = 历史委托（从未挂过标准器），兼容旧流程放行 */
            boolean legacyNoDevice,
            /** 当前是否允许调整挂接清单（仅收样） */
            boolean deviceEditable,
            /** 当前不可推进/挂接的原因列表，空表示服务端校验通过；前端展示、按钮仍以服务端为准 */
            List<String> blockReasons) {
    }

    /** 挂接标准器行：编号 / 证书有效期 / 状态 / 占用情况全部按服务端当前数据给出 */
    public record DeviceLinkView(
            Long linkId,
            Long deviceId,
            String deviceNo,
            String name,
            String model,
            String traceOrg,
            LocalDate certValidUntil,
            /** 证书剩余天数，负数已过期，null 未登记 */
            Integer remainDays,
            boolean expired,
            boolean expiring,
            String status,
            String statusName,
            LocalDateTime attachedAt,
            LocalDateTime releasedAt,
            String releaseReason,
            String releaseReasonName,
            /** 是否被本委托占用（活动行） */
            boolean active,
            /** 被哪笔未完成委托占用（本委托活动行时为自身），null = 空闲 */
            Long occupiedByOrderId,
            String occupiedByOrderNo,
            /** 以当前时间判定：该标准器能否挂接/放行，false 时 reason 给出具体原因 */
            boolean selectable,
            /** 不可选用/不可放行的具体原因，如「证书已于 2026-09-02 过期」 */
            String reason) {
    }

    /** 挂接抽屉返回：委托当前清单 + 全部可选标准器（含占用冲突标注） */
    public record OrderDevicesView(
            Long orderId,
            String orderNo,
            String status,
            String statusName,
            boolean editable,
            List<DeviceLinkView> active,
            List<DeviceLinkView> released,
            List<DeviceLinkView> candidates) {
    }

    /** 左侧待办队列里的单条 */
    public record QueueItemView(
            Long id,
            String orderNo,
            String instNo,
            String instrumentName,
            String clientName,
            LocalDate receiveDate,
            Integer daysWaiting,
            /** 占用标准器数量 */
            int deviceCount,
            /** 最早证书到期日（列表徽标用） */
            LocalDate earliestCertValidUntil,
            /** 不可推进原因（标准器停用/过期/被改证等），非空时队列项打红点 */
            List<String> blockReasons) {
    }

    /** 左侧待办队列：按流转状态分组 */
    public record QueueGroupView(
            String status,
            String statusName,
            Integer count,
            List<QueueItemView> items) {
    }

    /** 顶部概览：全站计数器 */
    public record OverviewView(
            Long instrumentTotal,
            Long instrumentDueSoon,
            Long instrumentOverdue,
            Long orderTotal,
            Long orderOpen,
            Long deviceExpiring,
            BigDecimal monthFee) {
    }

    /** 模块五：标准器 */
    public record DeviceView(
            Long id,
            String deviceNo,
            String name,
            String model,
            String measureRange,
            String traceOrg,
            LocalDate lastTraceDate,
            LocalDate certValidUntil,
            String status,
            String statusName,
            /** 证书剩余天数，负数表示已过期 */
            Integer remainDays,
            boolean expired,
            boolean expiring,
            String remark,
            /** 是否正被一笔未完成委托占用 */
            boolean occupied,
            /** 占用方委托单号，null = 空闲 */
            String occupiedByOrderNo) {
    }
}
