package io.github.chalmery.loopcraft.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.github.chalmery.loopcraft.agent.config.AgentProperties;
import io.github.chalmery.loopcraft.agent.dto.AgentViews;
import io.github.chalmery.loopcraft.agent.model.AgentRequest;
import io.github.chalmery.loopcraft.agent.model.RunBudget;
import io.github.chalmery.loopcraft.agent.model.RunListener;
import io.github.chalmery.loopcraft.agent.model.RunOutcome;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.llm.service.ChatModelFactory;
import io.github.chalmery.loopcraft.template.mapper.AgentTemplateMapper;
import io.github.chalmery.loopcraft.template.model.AgentTemplate;
import io.github.chalmery.loopcraft.tool.SkillTools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/** chat 编排：校验成员与模板链路，组装本次运行的工具表、预算与输入后交给 Agent 循环。 */
@Service
public class AgentChatService {

    private final IdentityService identity;

    private final ObjectProvider<AgentTemplateMapper> templateMappers;

    private final ChatModelFactory chatModelFactory;

    private final SkillTools skillTools;

    private final Agent agent;

    private final AgentProperties properties;

    public AgentChatService(
            IdentityService identity,
            ObjectProvider<AgentTemplateMapper> templateMappers,
            ChatModelFactory chatModelFactory,
            SkillTools skillTools,
            Agent agent,
            AgentProperties properties) {
        this.identity = identity;
        this.templateMappers = templateMappers;
        this.chatModelFactory = chatModelFactory;
        this.skillTools = skillTools;
        this.agent = agent;
        this.properties = properties;
    }

    /** 所有租户成员可发起；阻塞至终态后返回答案与计数，未成功抛 AgentRunException 交 HTTP 层映射。 */
    public AgentViews.ChatReply chat(UserAccount actor, long tenantId, long templateId, String message) {
        identity.tenant(actor, tenantId, false);
        AgentTemplate template = template(tenantId, templateId);
        Map<String, ToolCallback> tools = skillTools.forTemplate(tenantId, templateId);
        ChatModelFactory.Prepared prepared = chatModelFactory.prepare(tenantId, template, tools);
        AgentRequest request =
                new AgentRequest(
                        message,
                        template.getSystemPrompt(),
                        prepared.chatModel(),
                        prepared.options(),
                        tools,
                        budget(template));
        RunOutcome outcome = agent.run(request, RunListener.none());
        if (outcome.completed()) {
            return new AgentViews.ChatReply(outcome.answer(), outcome.iterations(), outcome.toolCalls());
        }
        throw new AgentRunException(outcome);
    }

    private AgentTemplate template(long tenantId, long templateId) {
        AgentTemplate template =
                templateMappers.getObject()
                        .selectOne(
                                Wrappers.<AgentTemplate>lambdaQuery()
                                        .eq(AgentTemplate::getTenantId, tenantId)
                                        .eq(AgentTemplate::getId, templateId));
        if (template == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前租户无此模板");
        }
        if (!Boolean.TRUE.equals(template.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模板已停用");
        }
        return template;
    }

    /** 模型轮次与总时长来自模板必填字段；0 视为配置错误拒绝，不进入循环。 */
    private RunBudget budget(AgentTemplate template) {
        Long maxIterations = template.getMaxIterations();
        BigDecimal timeoutMinutes = template.getTimeoutMinutes();
        if (maxIterations == null || maxIterations < 1 || timeoutMinutes == null
                || timeoutMinutes.signum() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "模板运行参数无效，请修正迭代与超时设置");
        }
        long runTimeoutNanos;
        try {
            runTimeoutNanos =
                    timeoutMinutes.multiply(BigDecimal.valueOf(60_000_000_000L)).longValueExact();
        } catch (ArithmeticException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模板超时设置过大");
        }
        return new RunBudget(
                maxIterations.intValue(),
                Duration.ofNanos(runTimeoutNanos),
                properties.modelRequestTimeout(),
                properties.toolTimeout(),
                properties.maxToolCalls());
    }
}
