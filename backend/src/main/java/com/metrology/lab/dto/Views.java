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
            /** 流转序号：0 收样 … 4 已发放 */
            Integer stepIndex,
            LocalDate certValidUntil,
            BigDecimal fee,
            String remark,
            LocalDateTime updatedAt) {
    }

    /** 左侧待办队列里的单条 */
    public record QueueItemView(
            Long id,
            String orderNo,
            String instNo,
            String instrumentName,
            String clientName,
            LocalDate receiveDate,
            Integer daysWaiting) {
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
            String remark) {
    }
}
