package io.github.chalmery.loopcraft.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import io.github.chalmery.loopcraft.shared.TraceIdContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 为每个请求建立追踪 ID，并通过响应头返回；下游日志和响应信封可复用该值。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    public static final String HEADER_NAME = "X-Trace-Id";
    private static final Pattern SAFE_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER_NAME);
        String traceId = supplied != null && SAFE_TRACE_ID.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
        TraceIdContext.set(traceId);
        response.setHeader(HEADER_NAME, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            TraceIdContext.clear();
        }
    }
}
