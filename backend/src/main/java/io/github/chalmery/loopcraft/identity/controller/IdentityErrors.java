package io.github.chalmery.loopcraft.identity.controller;

import io.github.chalmery.loopcraft.identity.service.LoginThrottledException;
import io.github.chalmery.loopcraft.shared.ApiResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = AuthController.class)
@org.springframework.core.annotation.Order(0)
public class IdentityErrors {
    @ExceptionHandler(LoginThrottledException.class)
    public ResponseEntity<ApiResponse<Void>> throttled(LoginThrottledException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(ApiResponse.failure(429, exception.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> unauthenticated() {
        return failure(HttpStatus.UNAUTHORIZED, "账号或密码错误，或登录已失效");
    }

    private ResponseEntity<ApiResponse<Void>> failure(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.failure(status.value(), message));
    }
}
