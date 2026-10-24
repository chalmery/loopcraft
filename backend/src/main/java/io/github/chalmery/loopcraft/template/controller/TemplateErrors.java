package io.github.chalmery.loopcraft.template.controller;

import io.github.chalmery.loopcraft.shared.ApiResponse;
import io.github.chalmery.loopcraft.shared.TraceIdContext;
import io.github.chalmery.loopcraft.template.service.TemplateFieldException;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 仅补充模板业务字段错误，通用异常继续使用 web 映射。 */
@Order(0)
@RestControllerAdvice(assignableTypes = TemplateController.class)
public class TemplateErrors {
    @ExceptionHandler(TemplateFieldException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> field(TemplateFieldException cause) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(400, cause.getReason(),
                Map.of("field", cause.getField()), TraceIdContext.current()));
    }
}
