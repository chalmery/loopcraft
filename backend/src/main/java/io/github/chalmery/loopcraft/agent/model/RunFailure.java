package io.github.chalmery.loopcraft.agent.model;

/** FAILED 状态下的稳定原因码（提案 7.2）。 */
public enum RunFailure {
    /** 模型服务或传输层错误。 */
    MODEL_ERROR,
    /** 空响应、空文本、id 非法、声称工具调用却无调用等协议异常。 */
    INVALID_MODEL_RESPONSE,
    /** 输出被长度上限截断，工具调用可能不完整。 */
    OUTPUT_LIMIT,
    /** 模型上下文超限；首版不自动压缩。 */
    CONTEXT_LIMIT,
    /** 工具执行出现不可预期异常，执行状态不明。 */
    TOOL_EXECUTION_UNCERTAIN
}
