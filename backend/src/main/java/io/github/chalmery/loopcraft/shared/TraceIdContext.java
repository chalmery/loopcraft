package io.github.chalmery.loopcraft.shared;

import org.slf4j.MDC;

/** 当前 HTTP 请求的追踪 ID，仅在请求处理线程中有效。 */
public final class TraceIdContext {
    private static final String MDC_KEY = "traceId";

    private TraceIdContext() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static void set(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
