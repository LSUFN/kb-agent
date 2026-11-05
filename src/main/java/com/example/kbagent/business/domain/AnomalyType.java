package com.example.kbagent.business.domain;

/** 异常事件类型，取值与 biz_anomaly_event.event_type 的 CHECK 约束一致。 */
public enum AnomalyType {
    /** 支付超时 */
    PAYMENT_TIMEOUT,
    /** 库存不一致 */
    INVENTORY_MISMATCH,
    /** 接口错误 */
    API_ERROR,
    /** 物流延迟 */
    LOGISTICS_DELAY
}
