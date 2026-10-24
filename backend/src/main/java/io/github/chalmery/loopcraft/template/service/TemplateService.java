package io.github.chalmery.loopcraft.template.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.chalmery.loopcraft.identity.mapper.TenantMapper;
import io.github.chalmery.loopcraft.identity.mapper.TenantMemberMapper;
import io.github.chalmery.loopcraft.identity.model.TenantMember;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.llm.mapper.LlmModelMapper;
import io.github.chalmery.loopcraft.llm.mapper.LlmSourceMapper;
import io.github.chalmery.loopcraft.llm.model.LlmModel;
import io.github.chalmery.loopcraft.llm.model.LlmSource;
import io.github.chalmery.loopcraft.skill.mapper.SkillMapper;
import io.github.chalmery.loopcraft.skill.mapper.SkillVersionMapper;
import io.github.chalmery.loopcraft.skill.model.Skill;
import io.github.chalmery.loopcraft.skill.model.SkillVersion;
import io.github.chalmery.loopcraft.template.dto.TemplateInput;
import io.github.chalmery.loopcraft.template.dto.TemplateViews;
import io.github.chalmery.loopcraft.template.mapper.AgentTemplateMapper;
import io.github.chalmery.loopcraft.template.mapper.TemplateRequestMapper;
import io.github.chalmery.loopcraft.template.mapper.TemplateSkillMapper;
import io.github.chalmery.loopcraft.template.model.AgentTemplate;
import io.github.chalmery.loopcraft.template.model.TemplateRequest;
import io.github.chalmery.loopcraft.template.model.TemplateSkill;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 模板管理与引用校验；不发起模型、工具或对象存储请求。 */
@Service
public class TemplateService {
    private final IdentityService identity;
    private final ObjectProvider<AgentTemplateMapper> templates;
    private final ObjectProvider<TemplateSkillMapper> bindings;
    private final ObjectProvider<TemplateRequestMapper> requests;
    private final ObjectProvider<TenantMapper> tenants;
    private final ObjectProvider<TenantMemberMapper> members;
    private final ObjectProvider<LlmModelMapper> models;
    private final ObjectProvider<LlmSourceMapper> sources;
    private final ObjectProvider<SkillMapper> skills;
    private final ObjectProvider<SkillVersionMapper> versions;
    private final Validator validator;
    private final ObjectMapper json;

    public TemplateService(IdentityService identity, ObjectProvider<AgentTemplateMapper> templates,
            ObjectProvider<TemplateSkillMapper> bindings, ObjectProvider<TemplateRequestMapper> requests,
            ObjectProvider<TenantMapper> tenants, ObjectProvider<TenantMemberMapper> members,
            ObjectProvider<LlmModelMapper> models, ObjectProvider<LlmSourceMapper> sources,
            ObjectProvider<SkillMapper> skills, ObjectProvider<SkillVersionMapper> versions,
            Validator validator, ObjectMapper json) {
        this.identity = identity;
        this.templates = templates;
        this.bindings = bindings;
        this.requests = requests;
        this.tenants = tenants;
        this.members = members;
        this.models = models;
        this.sources = sources;
        this.skills = skills;
        this.versions = versions;
        this.validator = validator;
        this.json = json;
    }

    public TemplateViews.ListPage list(UserAccount actor, long tenantId, int page, int size,
            String q, Boolean enabled) {
        member(actor, tenantId);
        var result = templates.getObject().selectPage(new Page<>(page, size),
                query(tenantId, q).eq(enabled != null, AgentTemplate::getEnabled, enabled)
                        .orderByDesc(AgentTemplate::getUpdatedAt, AgentTemplate::getId));
        long all = templates.getObject().selectCount(query(tenantId, q));
        long active = templates.getObject().selectCount(query(tenantId, q)
                .eq(AgentTemplate::getEnabled, true));
        var details = resolve(tenantId, result.getRecords());
        return new TemplateViews.ListPage(details.stream().map(d -> new TemplateViews.Summary(
                d.id(), d.name(), d.description(), d.enabled(), d.model().name(),
                d.skillBindings().size(), d.updatedAt(), d.available(), d.unavailableReasons())).toList(),
                result.getTotal(), page, size, new TemplateViews.Counts(all, active, all - active));
    }

    public TemplateViews.Detail detail(UserAccount actor, long tenantId, long id) {
        member(actor, tenantId);
        return resolve(tenantId, List.of(require(tenantId, id))).getFirst();
    }

    public String copyName(UserAccount actor, long tenantId, long id) {
        member(actor, tenantId);
        String base = require(tenantId, id).getName();
        int number = 1;
        while (true) {
            String suffix = number == 1 ? "（副本）" : "（副本 " + number + "）";
            String name = base.substring(0, Math.min(base.length(), 80 - suffix.length())) + suffix;
            if (templates.getObject().selectCount(query(tenantId, null)
                    .eq(AgentTemplate::getName, name)) == 0) {
                return name;
            }
            number++;
        }
    }

    public TemplateViews.Page<TemplateViews.ModelChoice> modelChoices(UserAccount actor,
            long tenantId, int page, int size, String q) {
        member(actor, tenantId);
        String keyword = keyword(q);
        var result = models.getObject().selectPage(new Page<>(page, size),
                Wrappers.<LlmModel>lambdaQuery().eq(LlmModel::getTenantId, tenantId)
                        .and(!keyword.isEmpty(), w -> w.like(LlmModel::getName, keyword)
                                .or().like(LlmModel::getModelId, keyword))
                        .orderByAsc(LlmModel::getSourceId, LlmModel::getId));
        Map<Long, LlmSource> connections = sourceMap(tenantId,
                result.getRecords().stream().map(LlmModel::getSourceId).toList());
        return new TemplateViews.Page<>(result.getRecords().stream()
                .map(m -> choice(m, connections.get(m.getSourceId()))).toList(),
                result.getTotal(), page, size);
    }

    @Transactional
    public TemplateViews.Detail save(UserAccount actor, long tenantId, Long id, TemplateInput raw) {
        member(actor, tenantId);
        var violations = validator.validate(raw);
        if (!violations.isEmpty()) {
            throw new TemplateFieldException(violations.iterator().next().getPropertyPath().toString(),
                    "请求字段格式或长度不合法");
        }
        TemplateInput input = normalize(raw);
        // 租户行串行化成员变更与保存，同一请求在提交成功后重试先返回原始响应。
        tenants.getObject().lockById(tenantId);
        member(actor, tenantId);
        String digest = digest((id == null ? "create" : "update:" + id) + json.writeValueAsString(input));
        TemplateRequest replay = requests.getObject().selectOne(Wrappers.<TemplateRequest>lambdaQuery()
                .eq(TemplateRequest::getTenantId, tenantId).eq(TemplateRequest::getActorId, actor.getId())
                .eq(TemplateRequest::getRequestId, input.requestId()));
        if (replay != null) {
            if (!replay.getDigest().equals(digest)) {
                throw conflict("相同请求标识不能提交不同的配置");
            }
            return json.readValue(replay.getResponseJson(), TemplateViews.Detail.class);
        }
        AgentTemplate previous = id == null ? null : require(tenantId, id);
        if (previous != null && !Objects.equals(previous.getRevision(), input.baseRevision())) {
            throw conflict("其他成员已更新模板，草稿已保留，请重新加载后再修改");
        }
        if (id == null && input.baseRevision() != null) {
            throw new TemplateFieldException("name", "新模板不能携带已有修订号");
        }
        if (templates.getObject().selectCount(query(tenantId, null)
                .eq(AgentTemplate::getName, input.name()).ne(id != null, AgentTemplate::getId, id)) > 0) {
            throw new TemplateFieldException("name", "当前租户已存在同名模板，请修改名称");
        }
        validateReferences(tenantId, previous, input);
        AgentTemplate item = previous == null ? new AgentTemplate() : previous;
        item.setTenantId(tenantId);
        item.setName(input.name());
        item.setDescription(input.description());
        item.setEnabled(input.enabled());
        item.setModelId(input.modelId());
        item.setSystemPrompt(input.systemPrompt());
        item.setTemperature(input.temperature());
        item.setContextBudgetTokens(input.contextBudgetTokens());
        item.setSummaryModelId(input.summaryModelId());
        item.setKeepRecentMessages(input.keepRecentMessages());
        item.setMaxIterations(input.maxIterations());
        item.setTimeoutMinutes(input.timeoutMinutes());
        item.setUpdatedAt(OffsetDateTime.now());
        if (previous == null) {
            item.setCreatedBy(actor.getId());
            item.setRevision(0L);
            templates.getObject().insert(item);
        } else {
            item.setRevision(item.getRevision() + 1);
            // 显式 set 可空字段，清空不会被 MyBatis-Plus 的忽略 null 策略吞掉。
            int changed = templates.getObject().update(Wrappers.<AgentTemplate>lambdaUpdate()
                    .eq(AgentTemplate::getTenantId, tenantId).eq(AgentTemplate::getId, id)
                    .eq(AgentTemplate::getRevision, input.baseRevision())
                    .set(AgentTemplate::getName, item.getName())
                    .set(AgentTemplate::getDescription, item.getDescription())
                    .set(AgentTemplate::getEnabled, item.getEnabled())
                    .set(AgentTemplate::getModelId, item.getModelId())
                    .set(AgentTemplate::getSystemPrompt, item.getSystemPrompt())
                    .set(AgentTemplate::getKeepRecentMessages, item.getKeepRecentMessages())
                    .set(AgentTemplate::getMaxIterations, item.getMaxIterations())
                    .set(AgentTemplate::getTimeoutMinutes, item.getTimeoutMinutes())
                    .set(AgentTemplate::getRevision, item.getRevision())
                    .set(AgentTemplate::getUpdatedAt, item.getUpdatedAt())
                    .set(AgentTemplate::getTemperature, input.temperature())
                    .set(AgentTemplate::getContextBudgetTokens, input.contextBudgetTokens())
                    .set(AgentTemplate::getSummaryModelId, input.summaryModelId()));
            if (changed != 1) {
                throw conflict("模板已变化，请重新加载");
            }
        }
        bindings.getObject().delete(Wrappers.<TemplateSkill>lambdaQuery()
                .eq(TemplateSkill::getTemplateId, item.getId()).eq(TemplateSkill::getTenantId, tenantId));
        int position = 0;
        for (var binding : input.skillBindings()) {
            var row = new TemplateSkill();
            row.setTenantId(tenantId);
            row.setTemplateId(item.getId());
            row.setSkillId(binding.skillId());
            row.setVersionNumber(binding.versionNumber());
            row.setPosition(position++);
            bindings.getObject().insert(row);
        }
        TemplateViews.Detail result = resolve(tenantId, List.of(item)).getFirst();
        var request = new TemplateRequest();
        request.setTenantId(tenantId);
        request.setActorId(actor.getId());
        request.setRequestId(input.requestId());
        request.setDigest(digest);
        request.setResponseJson(json.writeValueAsString(result));
        requests.getObject().insert(request);
        return result;
    }

    @Transactional
    public void delete(UserAccount actor, long tenantId, long id, long baseRevision) {
        member(actor, tenantId);
        tenants.getObject().lockById(tenantId);
        member(actor, tenantId);
        require(tenantId, id);
        int changed = templates.getObject().delete(Wrappers.<AgentTemplate>lambdaQuery()
                .eq(AgentTemplate::getTenantId, tenantId).eq(AgentTemplate::getId, id)
                .eq(AgentTemplate::getRevision, baseRevision));
        if (changed != 1) {
            throw conflict("其他成员已更新模板，请重新加载后删除");
        }
    }

    private void validateReferences(long tenantId, AgentTemplate previous, TemplateInput input) {
        Set<Long> modelIds = new HashSet<>(List.of(input.modelId()));
        if (input.summaryModelId() != null) {
            modelIds.add(input.summaryModelId());
        }
        Map<Long, LlmModel> selected = models.getObject().selectList(Wrappers.<LlmModel>lambdaQuery()
                .eq(LlmModel::getTenantId, tenantId).in(LlmModel::getId, modelIds))
                .stream().collect(Collectors.toMap(LlmModel::getId, Function.identity()));
        if (selected.size() != modelIds.size()) {
            throw notFound();
        }
        var connections = new HashMap<Long, LlmSource>();
        selected.values().stream().map(LlmModel::getSourceId).distinct().sorted().forEach(sourceId -> {
            LlmSource source = sources.getObject().lock(tenantId, sourceId);
            if (source == null) {
                throw notFound();
            }
            connections.put(sourceId, source);
        });
        // 取得连接锁后重新读模型，避免与同连接的停用/修改发生检查后变更。
        selected = models.getObject().selectList(Wrappers.<LlmModel>lambdaQuery()
                .eq(LlmModel::getTenantId, tenantId).in(LlmModel::getId, modelIds))
                .stream().collect(Collectors.toMap(LlmModel::getId, Function.identity()));
        LlmModel model = selected.get(input.modelId());
        if (model == null || selected.size() != modelIds.size()) {
            throw notFound();
        }
        validateModel(choice(model, connections.get(model.getSourceId())),
                previous == null ? null : previous.getModelId(), "modelId");
        if (input.summaryModelId() != null) {
            LlmModel summary = selected.get(input.summaryModelId());
            validateModel(choice(summary, connections.get(summary.getSourceId())),
                    previous == null ? null : previous.getSummaryModelId(), "summaryModelId");
        }
        if (model.getContextLimit() != null && input.contextBudgetTokens() != null
                && input.contextBudgetTokens() > model.getContextLimit()) {
            throw new TemplateFieldException("contextBudgetTokens", "上下文预算超过运行模型上限");
        }
        if (input.temperature() != null && Boolean.FALSE.equals(model.getTemperatureSupported())) {
            throw new TemplateFieldException("temperature", "当前模型不支持温度参数，请清空");
        }
        if (input.temperature() != null && model.getTemperatureMin() != null
                && (input.temperature().compareTo(model.getTemperatureMin()) < 0
                    || input.temperature().compareTo(model.getTemperatureMax()) > 0)) {
            throw new TemplateFieldException("temperature", "温度超出当前模型支持范围");
        }
        Map<Long, Integer> oldBindings = previous == null ? Map.of() : bindings.getObject()
                .selectList(Wrappers.<TemplateSkill>lambdaQuery().eq(TemplateSkill::getTemplateId, previous.getId()))
                .stream().collect(Collectors.toMap(TemplateSkill::getSkillId, TemplateSkill::getVersionNumber));
        var ids = new HashSet<Long>();
        for (var binding : input.skillBindings().stream()
                .sorted(java.util.Comparator.comparingLong(TemplateInput.Binding::skillId)).toList()) {
            if (!ids.add(binding.skillId())) {
                throw new TemplateFieldException("skillBindings", "同一 Skill 不能重复添加");
            }
            if (skills.getObject().lock(tenantId, binding.skillId()) == null) {
                throw notFound();
            }
            Skill skill = skills.getObject().selectById(binding.skillId());
            if (skill.getDeleted()) {
                throw notFound();
            }
            if (!skill.getEnabled() && !Objects.equals(oldBindings.get(binding.skillId()), binding.versionNumber())) {
                throw new TemplateFieldException("skillBindings", "新增或切换版本的 Skill 已停用，请移除或重新选择");
            }
            if (versions.getObject().selectCount(Wrappers.<SkillVersion>lambdaQuery()
                    .eq(SkillVersion::getTenantId, tenantId).eq(SkillVersion::getSkillId, binding.skillId())
                    .eq(SkillVersion::getNumber, binding.versionNumber())) != 1) {
                throw new TemplateFieldException("skillBindings", "所选 Skill 内容版本不存在");
            }
        }
    }

    private void validateModel(TemplateViews.ModelChoice model, Long previousId, String field) {
        if (!model.enabled() && !Objects.equals(model.id(), previousId)) {
            throw new TemplateFieldException(field, "新选择的模型或连接已停用，请重新选择");
        }
    }

    private List<TemplateViews.Detail> resolve(long tenantId, List<AgentTemplate> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        List<TemplateSkill> allBindings = bindings.getObject().selectList(Wrappers.<TemplateSkill>lambdaQuery()
                .eq(TemplateSkill::getTenantId, tenantId)
                .in(TemplateSkill::getTemplateId, records.stream().map(AgentTemplate::getId).toList())
                .orderByAsc(TemplateSkill::getPosition));
        Set<Long> modelIds = new HashSet<>();
        for (var row : records) {
            modelIds.add(row.getModelId());
            if (row.getSummaryModelId() != null) {
                modelIds.add(row.getSummaryModelId());
            }
        }
        List<LlmModel> modelRows = models.getObject().selectList(Wrappers.<LlmModel>lambdaQuery()
                .eq(LlmModel::getTenantId, tenantId).in(LlmModel::getId, modelIds));
        Map<Long, LlmSource> connections = sourceMap(tenantId, modelRows.stream().map(LlmModel::getSourceId).toList());
        Map<Long, TemplateViews.ModelChoice> choices = modelRows.stream().collect(Collectors.toMap(
                LlmModel::getId, m -> choice(m, connections.get(m.getSourceId()))));
        Map<Long, Skill> skillRows = allBindings.isEmpty() ? Map.of() : skills.getObject().selectList(
                Wrappers.<Skill>lambdaQuery().eq(Skill::getTenantId, tenantId)
                        .in(Skill::getId, allBindings.stream().map(TemplateSkill::getSkillId).toList()))
                .stream().collect(Collectors.toMap(Skill::getId, Function.identity()));
        var result = new ArrayList<TemplateViews.Detail>();
        for (var row : records) {
            var selectedSkills = allBindings.stream().filter(b -> b.getTemplateId().equals(row.getId()))
                    .map(b -> {
                        Skill skill = skillRows.get(b.getSkillId());
                        return new TemplateViews.SkillBinding(b.getSkillId(), b.getVersionNumber(),
                                skill.getName(), skill.getDescription(), skill.getEnabled() && !skill.getDeleted(),
                                skill.getLatestVersion());
                    }).toList();
            var main = choices.get(row.getModelId());
            var summary = row.getSummaryModelId() == null ? main : choices.get(row.getSummaryModelId());
            List<String> reasons = new ArrayList<>();
            modelIssues(main, "运行模型", reasons);
            if (!Objects.equals(main.id(), summary.id())) {
                modelIssues(summary, "摘要模型", reasons);
            }
            if (selectedSkills.stream().anyMatch(s -> !s.enabled())) {
                reasons.add("部分 Skill 已停用");
            }
            if (row.getContextBudgetTokens() != null && main.contextLimit() != null
                    && row.getContextBudgetTokens() > main.contextLimit()) {
                reasons.add("上下文预算超过模型当前上限");
            }
            if (row.getTemperature() != null && (!Boolean.TRUE.equals(main.temperatureSupported())
                    || main.temperatureMin() == null || main.temperatureMax() == null)) {
                reasons.add("请在模型接入补齐温度支持信息，或清空模板温度");
            } else if (row.getTemperature() != null
                    && (row.getTemperature().compareTo(main.temperatureMin()) < 0
                        || row.getTemperature().compareTo(main.temperatureMax()) > 0)) {
                reasons.add("温度超过模型当前范围");
            }
            result.add(new TemplateViews.Detail(row.getId(), row.getName(), row.getDescription(), row.getEnabled(),
                    row.getModelId(), row.getSystemPrompt(), row.getTemperature(), row.getContextBudgetTokens(),
                    row.getSummaryModelId(), row.getKeepRecentMessages(), row.getMaxIterations(), row.getTimeoutMinutes(),
                    row.getRevision(), row.getUpdatedAt(), main, summary, selectedSkills, reasons.isEmpty(), reasons));
        }
        return result;
    }

    private void modelIssues(TemplateViews.ModelChoice model, String label, List<String> reasons) {
        if (!model.enabled()) {
            reasons.add(label + "或连接已停用");
        }
        if (model.contextLimit() == null) {
            reasons.add(label + "尚未配置上下文上限");
        }
    }

    private Map<Long, LlmSource> sourceMap(long tenantId, List<Long> ids) {
        return ids.isEmpty() ? Map.of() : sources.getObject().selectList(Wrappers.<LlmSource>lambdaQuery()
                .eq(LlmSource::getTenantId, tenantId).in(LlmSource::getId, ids))
                .stream().collect(Collectors.toMap(LlmSource::getId, Function.identity()));
    }

    private TemplateViews.ModelChoice choice(LlmModel model, LlmSource source) {
        return new TemplateViews.ModelChoice(model.getId(), model.getSourceId(), model.getName(), source.getName(),
                model.getEnabled() && source.getEnabled(), model.getContextLimit(), model.getTemperatureSupported(),
                model.getTemperatureMin(), model.getTemperatureMax());
    }

    private void member(UserAccount actor, long tenantId) {
        identity.tenant(actor, tenantId, false);
        if (members.getObject().selectCount(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, tenantId).eq(TenantMember::getUserId, actor.getId())) == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请先加入该租户");
        }
    }

    private AgentTemplate require(long tenantId, long id) {
        AgentTemplate item = templates.getObject().selectOne(query(tenantId, null).eq(AgentTemplate::getId, id));
        if (item == null) {
            throw notFound();
        }
        return item;
    }

    private LambdaQueryWrapper<AgentTemplate> query(long tenantId, String q) {
        String keyword = keyword(q);
        return Wrappers.<AgentTemplate>lambdaQuery().eq(AgentTemplate::getTenantId, tenantId)
                .and(!keyword.isEmpty(), w -> w.like(AgentTemplate::getName, keyword)
                        .or().like(AgentTemplate::getDescription, keyword));
    }

    private String keyword(String q) {
        if (q != null && q.length() > 160) {
            throw new TemplateFieldException("q", "搜索内容过长");
        }
        return q == null ? "" : q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private TemplateInput normalize(TemplateInput input) {
        return new TemplateInput(input.requestId(), input.baseRevision(), input.name().strip(),
                input.description().strip(), input.enabled(), input.modelId(), input.systemPrompt(),
                number(input.temperature()), input.contextBudgetTokens(), input.summaryModelId(),
                input.keepRecentMessages(), input.maxIterations(), number(input.timeoutMinutes()), input.skillBindings());
    }

    private BigDecimal number(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException cause) {
            throw new IllegalStateException("SHA-256 不可用", cause);
        }
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "当前租户无此模板或关联资源");
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
