package io.github.chalmery.loopcraft.llm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.github.chalmery.loopcraft.identity.model.UserAccount;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.llm.dto.LlmRequests;
import io.github.chalmery.loopcraft.llm.dto.LlmViews;
import io.github.chalmery.loopcraft.llm.mapper.LlmModelMapper;
import io.github.chalmery.loopcraft.llm.mapper.LlmSourceMapper;
import io.github.chalmery.loopcraft.llm.model.LlmModel;
import io.github.chalmery.loopcraft.llm.model.LlmSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 来源和模型的租户权限、持久化及幂等导入。 */
@Service
public class LlmAccessService {

    private final IdentityService identity;

    private final ObjectProvider<LlmSourceMapper> sourceMappers;

    private final ObjectProvider<LlmModelMapper> modelMappers;

    private final LlmCredentialCipher cipher;

    private final LlmModelDiscovery discovery;

    public LlmAccessService(
            IdentityService identity,
            ObjectProvider<LlmSourceMapper> sourceMappers,
            ObjectProvider<LlmModelMapper> modelMappers,
            LlmCredentialCipher cipher,
            LlmModelDiscovery discovery) {
        this.identity = identity;
        this.sourceMappers = sourceMappers;
        this.modelMappers = modelMappers;
        this.cipher = cipher;
        this.discovery = discovery;
    }

    public LlmViews.Page<LlmViews.Source> sources(
            UserAccount actor, long tenantId, int page, int size, String q) {
        identity.tenant(actor, tenantId, false);
        var result =
                sources()
                        .selectPage(
                                new Page<>(page, size),
                                Wrappers.<LlmSource>lambdaQuery()
                                        .eq(LlmSource::getTenantId, tenantId)
                                        .like(
                                                q != null && !q.isBlank(),
                                                LlmSource::getName,
                                                keyword(q))
                                        .orderByDesc(LlmSource::getUpdatedAt, LlmSource::getId));
        var counts = new HashMap<Long, Long>();
        if (!result.getRecords().isEmpty()) {
            // 框架批量分组计数，聚合列是固定表达式，不接收客户端 SQL。
            models().selectMaps(
                            Wrappers.<LlmModel>query()
                                    .select("source_id", "COUNT(*) AS model_count")
                                    .eq("tenant_id", tenantId)
                                    .in(
                                            "source_id",
                                            result.getRecords().stream()
                                                    .map(LlmSource::getId)
                                                    .toList())
                                    .groupBy("source_id"))
                    .forEach(
                            row ->
                                    counts.put(
                                            ((Number) row.get("source_id")).longValue(),
                                            ((Number) row.get("model_count")).longValue()));
        }
        return new LlmViews.Page<>(
                result.getRecords().stream()
                        .map(s -> view(s, counts.getOrDefault(s.getId(), 0L)))
                        .toList(),
                result.getTotal(),
                page,
                size);
    }

    public LlmViews.Source source(UserAccount actor, long tenantId, long sourceId) {
        return view(source(actor, tenantId, sourceId, false, false));
    }

    @Transactional
    public LlmViews.Source createSource(
            UserAccount actor, long tenantId, LlmRequests.CreateSource input) {
        identity.tenant(actor, tenantId, true);
        LlmSource source = new LlmSource();
        source.setTenantId(tenantId);
        source.setName(LlmInputs.text(input.name(), "连接名称", 80));
        source.setSupplierName(LlmInputs.text(input.supplierName(), "供应商名称", 80));
        source.setProtocol(LlmInputs.protocol(input.protocol()));
        source.setBaseUrl(LlmInputs.baseUrl(input.baseUrl()));
        String key = LlmInputs.apiKey(input.apiKey());
        if (!key.isEmpty()) {
            source.setApiKeyCiphertext(cipher.encrypt(tenantId, key));
        }
        source.setEnabled(input.enabled() == null || input.enabled());
        source.setUpdatedAt(OffsetDateTime.now());
        sources().insert(source);
        return view(source, 0);
    }

    @Transactional
    public LlmViews.Source updateSource(
            UserAccount actor, long tenantId, long sourceId, Map<String, Object> patch) {
        LlmSource source = source(actor, tenantId, sourceId, true, true);
        LlmInputs.fields(
                patch,
                Set.of(
                        "name",
                        "supplierName",
                        "protocol",
                        "baseUrl",
                        "apiKey",
                        "clearApiKey",
                        "enabled"));
        if (patch.containsKey("name")) {
            source.setName(LlmInputs.text(patch.get("name"), "连接名称", 80));
        }
        if (patch.containsKey("supplierName")) {
            source.setSupplierName(LlmInputs.text(patch.get("supplierName"), "供应商名称", 80));
        }
        if (patch.containsKey("protocol")) {
            source.setProtocol(LlmInputs.protocol(patch.get("protocol")));
        }
        if (patch.containsKey("baseUrl")) {
            source.setBaseUrl(LlmInputs.baseUrl(patch.get("baseUrl")));
        }
        if (patch.containsKey("enabled")) {
            source.setEnabled(LlmInputs.bool(patch.get("enabled")));
        }
        boolean clear =
                patch.containsKey("clearApiKey") && LlmInputs.bool(patch.get("clearApiKey"));
        String key = LlmInputs.apiKey(patch.get("apiKey"));
        if (clear && !key.isEmpty()) {
            throw LlmInputs.invalid("更换和清除 API Key 不能同时执行");
        }
        if (clear) {
            source.setApiKeyCiphertext(null);
        } else if (!key.isEmpty()) {
            source.setApiKeyCiphertext(cipher.encrypt(tenantId, key));
        }
        source.setUpdatedAt(OffsetDateTime.now());
        sources()
                .update(
                        Wrappers.<LlmSource>lambdaUpdate()
                                .eq(LlmSource::getTenantId, tenantId)
                                .eq(LlmSource::getId, sourceId)
                                .set(LlmSource::getName, source.getName())
                                .set(LlmSource::getSupplierName, source.getSupplierName())
                                .set(LlmSource::getProtocol, source.getProtocol())
                                .set(LlmSource::getBaseUrl, source.getBaseUrl())
                                .set(LlmSource::getEnabled, source.getEnabled())
                                .set(LlmSource::getApiKeyCiphertext, source.getApiKeyCiphertext())
                                .set(LlmSource::getUpdatedAt, source.getUpdatedAt()));
        return view(source);
    }

    @Transactional
    public void deleteSource(UserAccount actor, long tenantId, long sourceId) {
        source(actor, tenantId, sourceId, true, true);
        // 模板的限制删除外键保护模型；任一引用存在时整个删除事务回滚。
        models().delete(
                        Wrappers.<LlmModel>lambdaQuery()
                                .eq(LlmModel::getTenantId, tenantId)
                                .eq(LlmModel::getSourceId, sourceId));
        sources()
                .delete(
                        Wrappers.<LlmSource>lambdaQuery()
                                .eq(LlmSource::getTenantId, tenantId)
                                .eq(LlmSource::getId, sourceId));
    }

    public LlmViews.Page<LlmViews.Model> models(
            UserAccount actor, long tenantId, long sourceId, int page, int size, String q) {
        source(actor, tenantId, sourceId, false, false);
        var query =
                Wrappers.<LlmModel>lambdaQuery()
                        .eq(LlmModel::getTenantId, tenantId)
                        .eq(LlmModel::getSourceId, sourceId);
        if (q != null && !q.isBlank()) {
            String keyword = keyword(q);
            query.and(
                    w ->
                            w.like(LlmModel::getName, keyword)
                                    .or()
                                    .like(LlmModel::getModelId, keyword));
        }
        var result =
                models().selectPage(
                                new Page<>(page, size),
                                query.orderByDesc(LlmModel::getUpdatedAt, LlmModel::getId));
        return new LlmViews.Page<>(
                result.getRecords().stream().map(this::view).toList(),
                result.getTotal(),
                page,
                size);
    }

    @Transactional
    public LlmViews.Model createModel(
            UserAccount actor, long tenantId, long sourceId, LlmRequests.ModelInput input) {
        source(actor, tenantId, sourceId, true, true);
        LlmViews.Model created = insertModel(tenantId, sourceId, input, "MANUAL");
        touchSource(tenantId, sourceId);
        return created;
    }

    @Transactional
    public LlmViews.Model updateModel(
            UserAccount actor,
            long tenantId,
            long sourceId,
            long modelId,
            Map<String, Object> patch) {
        source(actor, tenantId, sourceId, true, true);
        LlmModel model = model(tenantId, sourceId, modelId);
        LlmInputs.fields(patch, Set.of("name", "modelId", "enabled", "contextLimit",
                "temperatureSupported", "temperatureMin", "temperatureMax"));
        if (patch.containsKey("name")) {
            model.setName(LlmInputs.text(patch.get("name"), "模型名称", 80));
        }
        if (patch.containsKey("modelId")) {
            model.setModelId(LlmInputs.text(patch.get("modelId"), "模型 ID", 160));
        }
        if (patch.containsKey("enabled")) {
            model.setEnabled(LlmInputs.bool(patch.get("enabled")));
        }
        capabilities(model, patch);
        model.setUpdatedAt(OffsetDateTime.now());
        models().update(
                        Wrappers.<LlmModel>lambdaUpdate()
                                .eq(LlmModel::getTenantId, tenantId)
                                .eq(LlmModel::getSourceId, sourceId)
                                .eq(LlmModel::getId, modelId)
                                .set(LlmModel::getName, model.getName())
                                .set(LlmModel::getModelId, model.getModelId())
                                .set(LlmModel::getEnabled, model.getEnabled())
                                .set(LlmModel::getContextLimit, model.getContextLimit())
                                .set(LlmModel::getTemperatureSupported, model.getTemperatureSupported())
                                .set(LlmModel::getTemperatureMin, model.getTemperatureMin())
                                .set(LlmModel::getTemperatureMax, model.getTemperatureMax())
                                .set(LlmModel::getUpdatedAt, model.getUpdatedAt()));
        touchSource(tenantId, sourceId);
        return view(model);
    }

    @Transactional
    public void deleteModel(UserAccount actor, long tenantId, long sourceId, long modelId) {
        source(actor, tenantId, sourceId, true, true);
        model(tenantId, sourceId, modelId);
        models().delete(
                        Wrappers.<LlmModel>lambdaQuery()
                                .eq(LlmModel::getTenantId, tenantId)
                                .eq(LlmModel::getSourceId, sourceId)
                                .eq(LlmModel::getId, modelId));
        touchSource(tenantId, sourceId);
    }

    public LlmViews.Discovery discover(
            UserAccount actor, long tenantId, long sourceId, LlmRequests.Discover request) {
        LlmSource source = source(actor, tenantId, sourceId, true, false);
        if (source.getApiKeyCiphertext() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先配置连接的 API Key");
        }
        return discovery.discover(
                source.getProtocol(),
                source.getBaseUrl(),
                cipher.decrypt(tenantId, source.getApiKeyCiphertext()),
                request.cursor(),
                request.limit() == null ? 100 : request.limit());
    }

    @Transactional
    public LlmViews.Imported importModels(
            UserAccount actor, long tenantId, long sourceId, LlmRequests.ImportModels request) {
        source(actor, tenantId, sourceId, true, true);
        if (request.models() == null
                || request.models().isEmpty()
                || request.models().size() > 100) {
            throw LlmInputs.invalid("每次请选择 1–100 个模型");
        }
        var selected = new LinkedHashMap<String, LlmRequests.ModelInput>();
        for (var input : request.models()) {
            String id = LlmInputs.text(input.modelId(), "模型 ID", 160);
            selected.putIfAbsent(
                    id, new LlmRequests.ModelInput(LlmInputs.text(input.name(), "模型名称", 80), id));
        }
        var existing =
                models()
                        .selectList(
                                Wrappers.<LlmModel>lambdaQuery()
                                        .eq(LlmModel::getTenantId, tenantId)
                                        .eq(LlmModel::getSourceId, sourceId)
                                        .in(LlmModel::getModelId, selected.keySet()))
                        .stream()
                        .map(LlmModel::getModelId)
                        .collect(java.util.stream.Collectors.toSet());
        var created = new ArrayList<LlmViews.Model>();
        var skipped = new ArrayList<String>();
        selected.forEach(
                (id, input) -> {
                    if (existing.contains(id)) {
                        skipped.add(id);
                    } else {
                        created.add(insertModel(tenantId, sourceId, input, "DISCOVERY"));
                    }
                });
        if (!created.isEmpty()) {
            touchSource(tenantId, sourceId);
        }
        return new LlmViews.Imported(created, skipped);
    }

    private LlmViews.Model insertModel(
            long tenantId, long sourceId, LlmRequests.ModelInput input, String origin) {
        LlmModel model = new LlmModel();
        model.setTenantId(tenantId);
        model.setSourceId(sourceId);
        model.setName(LlmInputs.text(input.name(), "模型名称", 80));
        model.setModelId(LlmInputs.text(input.modelId(), "模型 ID", 160));
        model.setOrigin(origin);
        model.setEnabled(true);
        var metadata = new HashMap<String, Object>();
        metadata.put("contextLimit", input.contextLimit());
        metadata.put("temperatureSupported", input.temperatureSupported());
        metadata.put("temperatureMin", input.temperatureMin());
        metadata.put("temperatureMax", input.temperatureMax());
        capabilities(model, metadata);
        model.setUpdatedAt(OffsetDateTime.now());
        models().insert(model);
        return view(model);
    }

    private void touchSource(long tenantId, long sourceId) {
        sources()
                .update(
                        Wrappers.<LlmSource>lambdaUpdate()
                                .eq(LlmSource::getTenantId, tenantId)
                                .eq(LlmSource::getId, sourceId)
                                .set(LlmSource::getUpdatedAt, OffsetDateTime.now()));
    }

    private LlmSource source(
            UserAccount actor, long tenantId, long sourceId, boolean manage, boolean lock) {
        identity.tenant(actor, tenantId, manage);
        LlmSource source =
                lock
                        ? sources().lock(tenantId, sourceId)
                        : sources()
                                .selectOne(
                                        Wrappers.<LlmSource>lambdaQuery()
                                                .eq(LlmSource::getTenantId, tenantId)
                                                .eq(LlmSource::getId, sourceId));
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前租户无此连接");
        }
        return source;
    }

    private LlmModel model(long tenantId, long sourceId, long modelId) {
        LlmModel model =
                models().selectOne(
                                Wrappers.<LlmModel>lambdaQuery()
                                        .eq(LlmModel::getTenantId, tenantId)
                                        .eq(LlmModel::getSourceId, sourceId)
                                        .eq(LlmModel::getId, modelId));
        if (model == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前连接无此模型");
        }
        return model;
    }

    private String keyword(String q) {
        if (q == null) {
            return "";
        }
        if (q.length() > 160) {
            throw LlmInputs.invalid("搜索关键字过长");
        }
        return q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private LlmViews.Source view(LlmSource source) {
        long count =
                models().selectCount(
                                Wrappers.<LlmModel>lambdaQuery()
                                        .eq(LlmModel::getTenantId, source.getTenantId())
                                        .eq(LlmModel::getSourceId, source.getId()));
        return view(source, count);
    }

    private LlmViews.Source view(LlmSource source, long count) {
        return new LlmViews.Source(
                source.getId(),
                source.getName(),
                source.getSupplierName(),
                source.getProtocol(),
                source.getBaseUrl(),
                source.getApiKeyCiphertext() != null,
                source.getEnabled(),
                count,
                source.getUpdatedAt());
    }

    private LlmViews.Model view(LlmModel model) {
        return new LlmViews.Model(
                model.getId(),
                model.getSourceId(),
                model.getName(),
                model.getModelId(),
                model.getOrigin(),
                model.getEnabled(),
                model.getUpdatedAt(), model.getContextLimit(), model.getTemperatureSupported(),
                model.getTemperatureMin(), model.getTemperatureMax());
    }

    private void capabilities(LlmModel model, Map<String, Object> values) {
        if (values.containsKey("contextLimit")) {
            BigDecimal limit = decimal(values.get("contextLimit"));
            try {
                Long tokens = limit == null ? null : limit.longValueExact();
                if (tokens != null && (tokens <= 0 || tokens > 9007199254740991L)) {
                    throw LlmInputs.invalid("上下文上限必须为正整数");
                }
                model.setContextLimit(tokens);
            } catch (ArithmeticException exception) {
                throw LlmInputs.invalid("上下文上限必须为正整数");
            }
        }
        if (values.containsKey("temperatureSupported")) {
            model.setTemperatureSupported(values.get("temperatureSupported") == null
                    ? null : LlmInputs.bool(values.get("temperatureSupported")));
        }
        if (values.containsKey("temperatureMin")) {
            model.setTemperatureMin(decimal(values.get("temperatureMin")));
        }
        if (values.containsKey("temperatureMax")) {
            model.setTemperatureMax(decimal(values.get("temperatureMax")));
        }
        BigDecimal min = model.getTemperatureMin();
        BigDecimal max = model.getTemperatureMax();
        if ((min == null) != (max == null) || (min != null
                && (!Boolean.TRUE.equals(model.getTemperatureSupported())
                    || min.signum() < 0 || max.compareTo(min) < 0
                    || max.compareTo(BigDecimal.valueOf(100)) > 0))) {
            throw LlmInputs.invalid("温度范围需要在模型支持温度时成对填写，且上限不小于下限");
        }
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number)) {
            throw LlmInputs.invalid("模型能力字段必须为数值或空值");
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            throw LlmInputs.invalid("模型能力字段必须为有限数值");
        }
    }

    private LlmSourceMapper sources() {
        return sourceMappers.getObject();
    }

    private LlmModelMapper models() {
        return modelMappers.getObject();
    }
}
