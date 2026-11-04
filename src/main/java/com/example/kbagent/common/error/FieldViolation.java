package com.example.kbagent.common.error;

/** 参数校验失败时返回给客户端的字段级错误。 */
public record FieldViolation(String field, String message) {
}
