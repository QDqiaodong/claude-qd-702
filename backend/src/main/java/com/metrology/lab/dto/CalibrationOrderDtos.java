package com.metrology.lab.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块四（检定委托与流转单）入参。 */
public final class CalibrationOrderDtos {

    private CalibrationOrderDtos() {
    }

    @Data
    public static class OrderSaveReq {

        @NotBlank(message = "委托单号不能为空")
        private String orderNo;

        @NotBlank(message = "器具编号不能为空")
        private String instNo;

        /** 规程号 */
        @NotBlank(message = "检定规程不能为空")
        private String procNo;

        /** 收样日期 yyyy-MM-dd，可空默认今天 */
        private String receiveDate;

        /** 检定员 */
        private String verifier;

        /** 核验员 */
        private String checker;

        /** 实测数据项：多条 ; 分隔，单条 校准点|标准值|示值|示值误差 */
        private String measuredData;

        /** PASS / LIMITED / FAIL */
        private String conclusion;

        /** 证书编号 */
        private String certNo;

        private String remark;
    }

    /** 录入/修订实测数据与结论 */
    @Data
    public static class ResultReq {

        private String measuredData;

        private String conclusion;

        private String certNo;

        private String verifier;

        private String checker;
    }

    /** 出证时确认证书有效期与收费 */
    @Data
    public static class CertifyReq {

        /** 证书有效期至 yyyy-MM-dd，可空：按器具检定周期自动推算 */
        private String certValidUntil;
    }

    /**
     * 挂接清单保存（挂接 / 替换 / 解除统一入口：全量提交参与本次检定的标准器 id 列表）。
     * 空列表 = 解除全部；能否调整、是否冲突、证书是否有效一律由服务端在一个事务内最终判定。
     */
    @Data
    public static class DeviceAttachReq {

        /** 选中的标准器 id，可空/空数组表示解除全部挂接 */
        private List<Long> deviceIds;
    }
}
