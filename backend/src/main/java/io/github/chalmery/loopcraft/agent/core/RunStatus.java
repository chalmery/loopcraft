package io.github.chalmery.loopcraft.agent.core;

/** 一次 Agent 运行的终止状态（提案 7.2）。 */
public enum RunStatus {
    /** 模型协议正常结束且执行链收束；不自动证明答案正确。 */
    COMPLETED,
    /** 达到模板 maxIterations，最后一次仍要求工具，整批不执行。 */
    ITERATION_LIMIT,
    /** 达到全局工具调用总数上限，整批不执行。 */
    TOOL_CALL_LIMIT,
    /** 运行总时长或单次调用超时耗尽。 */
    TIMED_OUT,
    /** 内部取消（线程中断）；首版不对外提供取消接口。 */
    CANCELLED,
    /** 运行级失败，原因见 RunFailure。 */
    FAILED
}
