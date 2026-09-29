package com.metrology.lab.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块五（标准器）入参。 */
public final class StandardDeviceDtos {

    private StandardDeviceDtos() {
    }

    @Data
    public static class DeviceSaveReq {

        @NotBlank(message = "标准器编号不能为空")
        private String deviceNo;

        @NotBlank(message = "标准器名称不能为空")
        private String name;

        private String model;

        private String measureRange;

        private String traceOrg;

        /** 上次溯源日期 yyyy-MM-dd，可空 */
        private String lastTraceDate;

        /** 证书有效期至 yyyy-MM-dd */
        private String certValidUntil;

        /** IN_USE / STOPPED / TRACING，可空默认 IN_USE */
        private String status;

        private String remark;
    }

    @Data
    public static class StatusReq {

        @NotBlank(message = "状态不能为空")
        private String status;
    }

    /**
     * 修订标准器档案（模拟「证书被修订为更早到期」）：
     * 任意字段为空表示不改；服务端以最新数据重算所有占用委托的不可放行原因。
     */
    @Data
    public static class DeviceUpdateReq {

        private String name;

        private String model;

        private String measureRange;

        private String traceOrg;

        /** 上次溯源日期 yyyy-MM-dd，空串可清空 */
        private String lastTraceDate;

        /** 证书有效期至 yyyy-MM-dd，空串可清空 */
        private String certValidUntil;

        /** IN_USE / STOPPED / TRACING */
        private String status;

        private String remark;
    }
}
