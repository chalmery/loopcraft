package io.github.chalmery.loopcraft.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.github.chalmery.loopcraft.shared.TraceIdContext;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 通用处理记录可关联信息，同时不泄露上游异常消息。 */
class ApiErrorsTests {
    @Test
    void unexpectedErrorsLogTraceAndClassWithoutSecretMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiErrors.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        TraceIdContext.set("trace-for-test");
        try {
            var response =
                    new ApiErrors()
                            .unexpected(new IllegalStateException("sensitive-upstream-content"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().message()).isEqualTo("服务暂时不可用");
            assertThat(appender.list).hasSize(1);
            String message = appender.list.getFirst().getFormattedMessage();
            assertThat(message)
                    .contains("trace-for-test", "IllegalStateException")
                    .doesNotContain("sensitive-upstream-content");
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
        } finally {
            TraceIdContext.clear();
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void businessStatusRemainsAnApiEnvelope() {
        var response =
                new ApiErrors().status(new ResponseStatusException(HttpStatus.CONFLICT, "版本冲突"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo(409);
        assertThat(response.getBody().message()).isEqualTo("版本冲突");
    }
}
