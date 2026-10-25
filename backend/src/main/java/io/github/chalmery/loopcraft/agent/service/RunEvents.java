package io.github.chalmery.loopcraft.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.github.chalmery.loopcraft.agent.config.AgentProperties;
import io.github.chalmery.loopcraft.agent.mapper.AgentRunEventMapper;
import io.github.chalmery.loopcraft.agent.mapper.AgentRunMapper;
import io.github.chalmery.loopcraft.agent.model.AgentRun;
import io.github.chalmery.loopcraft.agent.model.AgentRunEvent;
import io.github.chalmery.loopcraft.agent.core.RunOutcome;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行事件的双层通道：先落 lc_agent_run_event（真相源与重放兜底），再写 Redis Stream
 * （实时热通道）。Redis 写入失败只影响订阅端实时性，不影响事件完整性；订阅端在
 * 热通道缺失时按序号回退轮询数据库。
 */
@Component
public class RunEvents {

    private static final Logger LOG = LoggerFactory.getLogger(RunEvents.class);

    private static final String STREAM_KEY_PREFIX = "loopcraft:agent:run:";

    private static final String STREAM_KEY_SUFFIX = ":events";

    private final ObjectProvider<AgentRunMapper> runMappers;

    private final ObjectProvider<AgentRunEventMapper> eventMappers;

    private final ObjectProvider<StringRedisTemplate> redisTemplates;

    private final AgentProperties properties;

    private final ObjectMapper json;

    public RunEvents(
            ObjectProvider<AgentRunMapper> runMappers,
            ObjectProvider<AgentRunEventMapper> eventMappers,
            ObjectProvider<StringRedisTemplate> redisTemplates,
            AgentProperties properties,
            ObjectMapper json) {
        this.runMappers = runMappers;
        this.eventMappers = eventMappers;
        this.redisTemplates = redisTemplates;
        this.properties = properties;
        this.json = json;
    }

    /** 受理：创建 RUNNING 运行行，并发送首事件。 */
    public void startRun(long tenantId, String runId, long templateId, long createdBy) {
        AgentRun run = new AgentRun();
        run.setId(runId);
        run.setTenantId(tenantId);
        run.setTemplateId(templateId);
        run.setCreatedBy(createdBy);
        run.setStatus("RUNNING");
        run.setIterations(0);
        run.setToolCalls(0);
        run.setCreatedAt(OffsetDateTime.now());
        runMappers.getObject().insert(run);
        publish(runId, "run_started", Map.of("templateId", templateId));
    }

    /** 追加事件：先落库取序号，再写热通道；返回事件序号（即 SSE 事件 ID）。 */
    public long publish(String runId, String type, Map<String, Object> payload) {
        AgentRunEvent event = new AgentRunEvent();
        event.setRunId(runId);
        event.setType(type);
        event.setPayload(json.writeValueAsString(payload));
        event.setCreatedAt(OffsetDateTime.now());
        eventMappers.getObject().insert(event);
        offerToStream(runId, event.getId(), type, event.getPayload());
        return event.getId();
    }

    /** 终态：更新运行行、发送 run_finished，并让热通道在保留窗口后过期。 */
    public void finishRun(String runId, RunOutcome outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", outcome.status().name());
        if (outcome.completed()) {
            payload.put("answer", outcome.answer());
        }
        if (outcome.failure() != null) {
            payload.put("reason", outcome.failure().name());
        }
        payload.put("iterations", outcome.iterations());
        payload.put("toolCalls", outcome.toolCalls());
        publish(runId, "run_finished", payload);
        runMappers.getObject()
                .update(
                        Wrappers.<AgentRun>lambdaUpdate()
                                .eq(AgentRun::getId, runId)
                                .eq(AgentRun::getStatus, "RUNNING")
                                .set(AgentRun::getStatus, outcome.status().name())
                                .set(AgentRun::getFailure,
                                        outcome.failure() == null ? null : outcome.failure().name())
                                .set(AgentRun::getAnswer, outcome.answer())
                                .set(AgentRun::getIterations, outcome.iterations())
                                .set(AgentRun::getToolCalls, outcome.toolCalls())
                                .set(AgentRun::getFinishedAt, OffsetDateTime.now()));
        expireStream(runId);
    }

    /** 按序号读取事件，SSE 重放与热通道缺失时的轮询兜底共用。 */
    public List<AgentRunEvent> history(String runId, long afterSeq) {
        return eventMappers.getObject()
                .selectList(
                        Wrappers.<AgentRunEvent>lambdaQuery()
                                .eq(AgentRunEvent::getRunId, runId)
                                .gt(AgentRunEvent::getId, afterSeq)
                                .orderByAsc(AgentRunEvent::getId));
    }

    /** 运行行查询；租户不匹配或不存在返回 null，由调用方决定 404。 */
    public AgentRun run(long tenantId, String runId) {
        return runMappers.getObject()
                .selectOne(
                        Wrappers.<AgentRun>lambdaQuery()
                                .eq(AgentRun::getTenantId, tenantId)
                                .eq(AgentRun::getId, runId));
    }

    /** 热通道阻塞读取；offset 通常为 "$"（只看新事件）或上一条 entry id。 */
    public List<StreamEntry> readStream(String runId, String offset, Duration block) {
        StringRedisTemplate redis = redisTemplates.getIfAvailable();
        if (redis == null) {
            return List.of();
        }
        List<MapRecord<String, Object, Object>> records =
                redis.opsForStream()
                        .read(
                                StreamReadOptions.empty().block(block),
                                StreamOffset.create(streamKey(runId), ReadOffset.from(offset)));
        if (records == null) {
            return List.of();
        }
        return records.stream()
                .map(
                        record -> {
                            Map<Object, Object> fields = record.getValue();
                            return new StreamEntry(
                                    record.getId().getValue(),
                                    Long.parseLong(String.valueOf(fields.get("seq"))),
                                    String.valueOf(fields.get("type")),
                                    String.valueOf(fields.get("data")));
                        })
                .toList();
    }

    /** 热通道是否仍存在；不存在时订阅端回退数据库轮询。 */
    public boolean streamAlive(String runId) {
        StringRedisTemplate redis = redisTemplates.getIfAvailable();
        return redis != null && Boolean.TRUE.equals(redis.hasKey(streamKey(runId)));
    }

    private void offerToStream(String runId, long seq, String type, String payload) {
        StringRedisTemplate redis = redisTemplates.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("seq", String.valueOf(seq));
            fields.put("type", type);
            fields.put("data", payload);
            String key = streamKey(runId);
            redis.opsForStream()
                    .add(
                            org.springframework.data.redis.connection.stream.StreamRecords
                                    .newRecord()
                                    .in(key)
                                    .ofMap(fields));
            redis.opsForStream().trim(key, properties.eventsStreamMaxLen(), true);
        } catch (RuntimeException error) {
            // 热通道可丢：数据库已有完整事件，订阅端会按序号回退轮询。
            LOG.warn("运行事件写入 Redis Stream 失败，runId={} seq={} type={}", runId, seq, type);
        }
    }

    private void expireStream(String runId) {
        StringRedisTemplate redis = redisTemplates.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.expire(streamKey(runId), properties.eventsStreamTtl());
        } catch (RuntimeException error) {
            LOG.warn("运行事件通道过期设置失败，runId={}", runId);
        }
    }

    private String streamKey(String runId) {
        return STREAM_KEY_PREFIX + runId + STREAM_KEY_SUFFIX;
    }

    /** 热通道条目；recordId 供订阅端推进读取位置，seq 与数据库事件序号一致供去重续传。 */
    public record StreamEntry(String recordId, long seq, String type, String payload) {
    }
}
