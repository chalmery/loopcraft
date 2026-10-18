package io.github.chalmery.loopcraft.shared;

/** 统一 API 响应结构；业务错误码沿用 HTTP 状态码，成功使用 0。 */
public record ApiResponse<T>(int code, String message, T data, String traceId) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, "success", data, TraceIdContext.current());
    }

    public static ApiResponse<Void> failure(int code, String message) {
        return new ApiResponse<>(code, message, null, TraceIdContext.current());
    }
}
