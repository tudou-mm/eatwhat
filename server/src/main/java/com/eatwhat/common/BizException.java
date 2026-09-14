package com.eatwhat.common;

/**
 * 业务异常。由 GlobalExceptionHandler 统一转成 {code,msg}。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String msg) {
        super(msg);
        this.code = 400;
    }

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public int getCode() { return code; }
}
