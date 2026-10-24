package io.github.chalmery.loopcraft.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.chalmery.loopcraft.shared.TraceIdContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

class TraceIdFilterTests {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearContext() {
        TraceIdContext.clear();
    }

    @Test
    void returnsSuppliedTraceIdAndMakesItAvailableDuringRequest() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER_NAME, "review-42");
        var response = new MockHttpServletResponse();
        filter.doFilter(
                request,
                response,
                (input, output) -> assertThat(TraceIdContext.current()).isEqualTo("review-42"));
        assertThat(response.getHeader(TraceIdFilter.HEADER_NAME)).isEqualTo("review-42");
        assertThat(TraceIdContext.current()).isNull();
    }

    @Test
    void generatesSafeTraceIdsForMissingOrInvalidHeaders() throws Exception {
        for (String value : new String[] {null, "", "unsafe\ninjected", "x".repeat(65)}) {
            var request = new MockHttpServletRequest();
            if (value != null) {
                request.addHeader(TraceIdFilter.HEADER_NAME, value);
            }
            var response = new MockHttpServletResponse();
            filter.doFilter(
                    request,
                    response,
                    (input, output) ->
                            assertThat(TraceIdContext.current()).matches("[0-9a-f-]{36}"));
            assertThat(response.getHeader(TraceIdFilter.HEADER_NAME)).matches("[0-9a-f-]{36}");
            assertThat(TraceIdContext.current()).isNull();
        }
    }

    @Test
    void propagatesFailuresAndClearsTraceContext() {
        var failure = new IOException("下游失败");
        assertThatThrownBy(
                        () ->
                                filter.doFilter(
                                        new MockHttpServletRequest(),
                                        new MockHttpServletResponse(),
                                        (input, output) -> {
                                            throw failure;
                                        }))
                .isSameAs(failure);
        assertThat(TraceIdContext.current()).isNull();
    }
}
