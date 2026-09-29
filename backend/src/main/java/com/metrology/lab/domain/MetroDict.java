package com.metrology.lab.domain;

import java.util.List;
import java.util.Map;

/**
 * 计量检定所词典与业务规则。
 *
 * <p>状态字典、收费与流转规则集中在此，便于统一调整。
 * 注意：本仓不使用 common.Dict（那份是别处的模板），业务字典全部在本类中定义。
 */
public final class MetroDict {

    private MetroDict() {
    }

    // ---------------- 器具状态 ----------------
    private static final Map<String, String> INSTRUMENT_STATUS = Map.of(
            "IN_USE", "在用",
            "STOPPED", "停用",
            "IN_CALIBRATION", "送检中",
            "SCRAPPED", "报废");

    // ---------------- 委托单流转状态（严格顺序） ----------------
    public static final List<String> FLOW_ORDER = List.of(
            "RECEIVED", "CALIBRATING", "VERIFYING", "CERTIFYING", "ISSUED");

    /** 作废：终态之一，不在 FLOW_ORDER 推进链上，仅用于展示与拦截 */
    public static final String STATUS_VOID = "VOID";

    private static final Map<String, String> FLOW_STATUS = Map.of(
            "RECEIVED", "收样",
            "CALIBRATING", "检定中",
            "VERIFYING", "核验",
            "CERTIFYING", "出证",
            "ISSUED", "已发放",
            "VOID", "已作废");

    /** 挂接释放原因（lab_order_device.release_reason） */
    private static final Map<String, String> RELEASE_REASON = Map.of(
            "REPLACE", "清单替换",
            "ROLLBACK_RECEIVED", "检定退回收样",
            "VOID", "委托作废",
            "ISSUED", "完成发放");

    // ---------------- 检定结论 ----------------
    private static final Map<String, String> CONCLUSION = Map.of(
            "PASS", "合格",
            "LIMITED", "限用",
            "FAIL", "不合格");

    // ---------------- 结算方式 ----------------
    private static final Map<String, String> SETTLE_TYPE = Map.of(
            "CASH", "现结",
            "MONTHLY", "月结");

    // ---------------- 标准器状态 ----------------
    private static final Map<String, String> DEVICE_STATUS = Map.of(
            "IN_USE", "在用",
            "STOPPED", "停用",
            "TRACING", "送检");

    /** 到期预警阈值：器具距下次到期日不足 30 天即预警 */
    public static final int INSTRUMENT_DUE_WARN_DAYS = 30;

    /** 标准器证书有效期不足 60 天即需要安排溯源 */
    public static final int DEVICE_VALID_WARN_DAYS = 60;

    public static boolean isValidInstrumentStatus(String status) {
        return status != null && INSTRUMENT_STATUS.containsKey(status);
    }

    public static String instrumentStatusName(String status) {
        return status == null ? "" : INSTRUMENT_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidFlowStatus(String status) {
        return status != null && FLOW_STATUS.containsKey(status);
    }

    public static String flowStatusName(String status) {
        return status == null ? "" : FLOW_STATUS.getOrDefault(status, status);
    }

    public static int flowIndex(String status) {
        int index = FLOW_ORDER.indexOf(status);
        return index < 0 ? 0 : index;
    }

    /** 作废等非流转链状态不参与推进/回退序号 */
    public static boolean isFlowStatus(String status) {
        return status != null && FLOW_ORDER.contains(status);
    }

    public static String releaseReasonName(String reason) {
        return reason == null ? "" : RELEASE_REASON.getOrDefault(reason, reason);
    }

    public static boolean isValidConclusion(String conclusion) {
        return conclusion != null && CONCLUSION.containsKey(conclusion);
    }

    public static String conclusionName(String conclusion) {
        return conclusion == null ? "" : CONCLUSION.getOrDefault(conclusion, conclusion);
    }

    public static boolean isValidSettleType(String type) {
        return type != null && SETTLE_TYPE.containsKey(type);
    }

    public static String settleTypeName(String type) {
        return type == null ? "" : SETTLE_TYPE.getOrDefault(type, type);
    }

    public static boolean isValidDeviceStatus(String status) {
        return status != null && DEVICE_STATUS.containsKey(status);
    }

    public static String deviceStatusName(String status) {
        return status == null ? "" : DEVICE_STATUS.getOrDefault(status, status);
    }
}
