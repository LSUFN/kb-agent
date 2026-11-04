package com.example.kbagent.common.error;

import java.io.InterruptedIOException;
import java.util.concurrent.TimeoutException;

/** 把调用模型服务时的异常归类为对外的错误码（502）。具体原因只写日志，不返回给客户端。 */
public final class UpstreamErrors {

    private UpstreamErrors() {
    }

    public static ErrorCode classify(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            // SocketTimeoutException、HttpTimeoutException 都是 InterruptedIOException 的子类
            if (t instanceof InterruptedIOException || t instanceof TimeoutException) {
                return ErrorCode.UPSTREAM_TIMEOUT;
            }
        }
        return ErrorCode.UPSTREAM_ERROR;
    }
}
