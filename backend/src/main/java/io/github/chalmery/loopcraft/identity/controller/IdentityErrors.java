package io.github.chalmery.loopcraft.identity.controller;

import io.github.chalmery.loopcraft.shared.ApiResponse;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackageClasses = {AuthController.class, io.github.chalmery.loopcraft.llm.controller.LlmAccessController.class})
public class IdentityErrors {
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> unauthenticated() {
        return failure(HttpStatus.UNAUTHORIZED, "账号或密码错误，或登录已失效");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> conflict() {
        return failure(HttpStatus.CONFLICT, "记录名称或 ID 已存在，或存在关联业务数据；有关联时请改为停用");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            HandlerMethodValidationException.class, TypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> invalid() {
        // 不回显密码、SQL 或异常中的参数。
        return failure(HttpStatus.BAD_REQUEST, "请求字段格式或长度不合法");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> status(ResponseStatusException exception) {
        int code = exception.getStatusCode().value();
        HttpStatus status = HttpStatus.resolve(code);
        String message = exception.getReason() != null ? exception.getReason()
                : status == null ? "请求处理失败" : status.getReasonPhrase();
        return ResponseEntity.status(exception.getStatusCode())
                .body(ApiResponse.failure(code, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected() {
        return failure(HttpStatus.INTERNAL_SERVER_ERROR, "服务暂时不可用");
    }

    private ResponseEntity<ApiResponse<Void>> failure(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.failure(status.value(), message));
    }
}
