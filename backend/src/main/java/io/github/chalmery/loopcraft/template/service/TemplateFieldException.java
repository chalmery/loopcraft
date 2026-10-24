package io.github.chalmery.loopcraft.template.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 允许表单将业务校验定位到对应字段。 */
public class TemplateFieldException extends ResponseStatusException {
    private final String field;

    public TemplateFieldException(String field, String message) {
        super(HttpStatus.BAD_REQUEST, message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
