package com.eatwhat.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理：保证前端永远拿到 {code,msg,data} 结构。
 *
 * HTTP 状态码**与业务码保持一致**（401 就真的是 401）。
 * 这样客户端、网关、监控才能按标准语义处理；只在 body 里写 code 而
 * HTTP 一律 200，是所有调用方都得额外写一层判断的根源。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(httpStatus(e.getCode()))
                .body(R.fail(e.getCode(), e.getMessage()));
    }

    /** @Valid 校验失败：取第一条错误信息，前端直接 toast */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getDefaultMessage())
                .orElse("参数不合法");
        return ResponseEntity.badRequest().body(R.fail(400, msg));
    }

    /**
     * multipart 超限会被 Spring 在进 Controller 之前就拦下，
     * 这里翻译成和业务一致的提示，避免前端收到一坨 Tomcat 的错误页。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest()
                .body(R.fail(400, "文件太大，已超过服务端限制（图片 5MB / 视频 20MB）"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleOther(Exception e) {
        log.error("未捕获异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(500, "服务器内部错误：" + e.getMessage()));
    }

    /** 业务码恰好是标准 HTTP 码就用它，否则（例如自定义 1001）退回 200 + code */
    private static HttpStatus httpStatus(int code) {
        HttpStatus s = HttpStatus.resolve(code);
        return s == null ? HttpStatus.OK : s;
    }
}
