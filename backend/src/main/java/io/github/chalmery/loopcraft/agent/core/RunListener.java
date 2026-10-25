package io.github.chalmery.loopcraft.agent.core;

/** 循环过程观察接口；首版供测试与诊断使用，外围通道（SSE 等）接入时消费同一接口。 */
public interface RunListener {

    /** 一次模型请求即将发起。 */
    default void iterationStarted(int iteration) {}

    /** 一个工具调用通过查找即将执行；参数不外发。 */
    default void toolCalled(String callId, String name) {}

    /** 一个工具调用结束；ok/truncated 来自结果 envelope，不包含结果内容。 */
    default void toolFinished(String callId, String name, boolean ok, boolean truncated, long durationMs) {}

    /** 空实现，避免无观察需求的调用方写匿名类。 */
    static RunListener none() {
        return new RunListener() {};
    }
}
