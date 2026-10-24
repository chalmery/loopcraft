package io.github.chalmery.loopcraft.skill.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.github.chalmery.loopcraft.identity.mapper.TenantMapper;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.skill.dto.SkillInputs;
import io.github.chalmery.loopcraft.skill.dto.SkillViews;
import io.github.chalmery.loopcraft.skill.mapper.SkillMapper;
import io.github.chalmery.loopcraft.skill.mapper.SkillTagMapper;
import io.github.chalmery.loopcraft.skill.mapper.SkillVersionMapper;
import io.github.chalmery.loopcraft.skill.model.Skill;
import io.github.chalmery.loopcraft.skill.model.SkillTag;
import io.github.chalmery.loopcraft.skill.model.SkillVersion;
import io.github.chalmery.loopcraft.storage.model.FileDownload;
import io.github.chalmery.loopcraft.storage.model.ObjectLocation;
import io.github.chalmery.loopcraft.storage.service.FileStorage;
import io.github.chalmery.loopcraft.storage.service.FileStorageException;
import io.github.chalmery.loopcraft.template.mapper.TemplateSkillMapper;
import io.github.chalmery.loopcraft.template.model.TemplateSkill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** 元数据与文件分离；数据库锁保护版本提交，文件对象只追加不覆盖。 */
@Service
public class SkillService {

    private static final Logger LOG = LoggerFactory.getLogger(SkillService.class);

    private final IdentityService identity;

    private final ObjectProvider<SkillMapper> skillMappers;

    private final ObjectProvider<SkillTagMapper> tagMappers;

    private final ObjectProvider<SkillVersionMapper> versionMappers;

    private final ObjectProvider<TenantMapper> tenantMappers;

    private final ObjectProvider<FileStorage> storage;

    private final SkillObjectUploads uploads;

    private final ObjectProvider<TemplateSkillMapper> templateBindings;

    public SkillService(
            IdentityService identity,
            ObjectProvider<SkillMapper> skillMappers,
            ObjectProvider<SkillTagMapper> tagMappers,
            ObjectProvider<SkillVersionMapper> versionMappers,
            ObjectProvider<TenantMapper> tenantMappers,
            ObjectProvider<FileStorage> storage,
            SkillObjectUploads uploads,
            ObjectProvider<TemplateSkillMapper> templateBindings) {
        this.identity = identity;
        this.skillMappers = skillMappers;
        this.tagMappers = tagMappers;
        this.versionMappers = versionMappers;
        this.tenantMappers = tenantMappers;
        this.storage = storage;
        this.uploads = uploads;
        this.templateBindings = templateBindings;
    }

    public SkillViews.ListPage list(
            UserAccount actor, long tenantId, int page, int size, String query) {
        return list(actor, tenantId, page, size, query, null);
    }

    public SkillViews.ListPage list(
            UserAccount actor, long tenantId, int page, int size, String query, String origin) {
        identity.tenant(actor, tenantId, false);
        if (origin != null && !List.of("CUSTOM", "SYSTEM").contains(origin)) {
            throw SkillPackages.invalid("无效的 Skill 来源");
        }
        String q = query == null ? "" : query.trim();
        if (q.length() > 160) {
            throw SkillPackages.invalid("搜索条件过长");
        }
        List<Long> tagged =
                q.isEmpty()
                        ? List.of()
                        : tags()
                                .selectList(
                                        Wrappers.<SkillTag>lambdaQuery()
                                                .eq(SkillTag::getTenantId, tenantId)
                                                .like(SkillTag::getName, escape(q)))
                                .stream()
                                .map(SkillTag::getSkillId)
                                .distinct()
                                .toList();
        Page<Skill> result =
                skills().selectPage(
                                new Page<>(page, size),
                                Wrappers.<Skill>lambdaQuery()
                                        .eq(Skill::getTenantId, tenantId)
                                        .eq(Skill::getDeleted, false)
                                        .eq(origin != null, Skill::getOrigin, origin)
                                        .and(
                                                !q.isEmpty(),
                                                condition ->
                                                        condition
                                                                .like(Skill::getName, escape(q))
                                                                .or()
                                                                .like(Skill::getSlug, escape(q))
                                                                .or()
                                                                .like(
                                                                        Skill::getDescription,
                                                                        escape(q))
                                                                .or(!tagged.isEmpty())
                                                                .in(
                                                                        !tagged.isEmpty(),
                                                                        Skill::getId,
                                                                        tagged))
                                        .orderByDesc(Skill::getUpdatedAt, Skill::getId));
        List<Long> ids = result.getRecords().stream().map(Skill::getId).toList();
        Map<Long, List<SkillTag>> grouped =
                ids.isEmpty()
                        ? Map.of()
                        : tags()
                                .selectList(
                                        Wrappers.<SkillTag>lambdaQuery()
                                                .eq(SkillTag::getTenantId, tenantId)
                                                .in(SkillTag::getSkillId, ids)
                                                .orderByAsc(SkillTag::getId))
                                .stream()
                                .collect(Collectors.groupingBy(SkillTag::getSkillId));
        // 一次读取本页各 Skill 的最新版本，避免逐条查库或加载所有历史版本。
        Map<Long, SkillVersion> latest =
                ids.isEmpty()
                        ? Map.of()
                        : versions()
                                .selectList(
                                        Wrappers.<SkillVersion>lambdaQuery()
                                                .eq(SkillVersion::getTenantId, tenantId)
                                                .and(
                                                        condition -> {
                                                            for (Skill skill :
                                                                    result.getRecords()) {
                                                                condition
                                                                        .or()
                                                                        .nested(
                                                                                pair ->
                                                                                        pair.eq(
                                                                                                        SkillVersion
                                                                                                                ::getSkillId,
                                                                                                        skill
                                                                                                                .getId())
                                                                                                .eq(
                                                                                                        SkillVersion
                                                                                                                ::getNumber,
                                                                                                        skill
                                                                                                                .getLatestVersion()));
                                                            }
                                                        }))
                                .stream()
                                .collect(
                                        Collectors.toMap(SkillVersion::getSkillId, value -> value));
        long custom =
                skills().selectCount(
                                Wrappers.<Skill>lambdaQuery()
                                        .eq(Skill::getTenantId, tenantId)
                                        .eq(Skill::getDeleted, false)
                                        .eq(Skill::getOrigin, "CUSTOM"));
        long system =
                skills().selectCount(
                                Wrappers.<Skill>lambdaQuery()
                                        .eq(Skill::getTenantId, tenantId)
                                        .eq(Skill::getDeleted, false)
                                        .eq(Skill::getOrigin, "SYSTEM"));
        return new SkillViews.ListPage(
                result.getRecords().stream()
                        .map(
                                s ->
                                        view(
                                                s,
                                                grouped.getOrDefault(s.getId(), List.of()).stream()
                                                        .map(SkillTag::getName)
                                                        .toList(),
                                                latest.get(s.getId())))
                        .toList(),
                result.getTotal(),
                page,
                size,
                new SkillViews.Counts(custom, system));
    }

    public SkillViews.Summary detail(UserAccount actor, long tenantId, long skillId) {
        return view(require(actor, tenantId, skillId, false));
    }

    public SkillViews.Summary create(
            UserAccount actor, long tenantId, SkillInputs.Create input, Map<String, byte[]> files)
            throws IOException {
        identity.tenant(actor, tenantId, false);
        if (input == null) {
            throw SkillPackages.invalid("提交参数不能为空");
        }
        String requestId = requestId(input.requestId());
        String slug = text(input.slug(), "Skill 标识", 64, false);
        String name = text(input.name(), "名称", 80, false);
        String description = text(input.description(), "描述", 1000, true);
        List<String> tagNames = cleanTags(input.tags());
        if (!slug.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw SkillPackages.invalid("标识仅允许小写字母、数字和连接符");
        }
        PackageData content = packageData(files);
        String creationDigest =
                digest(
                        (slug
                                        + "\0"
                                        + name
                                        + "\0"
                                        + description
                                        + "\0"
                                        + content.digest()
                                        + "\0"
                                        + String.join("\0", tagNames))
                                .getBytes(StandardCharsets.UTF_8));
        return uploads.publish(
                tenantId,
                content.archive(),
                location -> {
                    tenantMappers.getObject().lockById(tenantId);
                    identity.tenant(actor, tenantId, false);
                    Skill existing =
                            skills().selectOne(
                                            Wrappers.<Skill>lambdaQuery()
                                                    .eq(Skill::getTenantId, tenantId)
                                                    .eq(Skill::getCreationRequest, requestId));
                    if (existing != null) {
                        if (existing.getDeleted()
                                || !existing.getCreatedBy().equals(actor.getId())) {
                            throw conflict("创建请求标识已使用");
                        }
                        if (!creationDigest.equals(existing.getCreationDigest())) {
                            throw conflict("重复请求内容不一致，历史记录请使用新的请求标识");
                        }
                        return view(existing);
                    }
                    if (skills().selectCount(
                                            Wrappers.<Skill>lambdaQuery()
                                                    .eq(Skill::getTenantId, tenantId)
                                                    .eq(Skill::getSlug, slug)
                                                    .eq(Skill::getDeleted, false))
                            > 0) {
                        throw conflict("当前租户已有相同标识");
                    }
                    Skill skill = new Skill();
                    skill.setTenantId(tenantId);
                    skill.setSlug(slug);
                    skill.setName(name);
                    skill.setDescription(description);
                    skill.setOrigin("CUSTOM");
                    skill.setEnabled(true);
                    skill.setDeleted(false);
                    skill.setLatestVersion(0);
                    skill.setRevision(0L);
                    skill.setCreationRequest(requestId);
                    skill.setCreationDigest(creationDigest);
                    skill.setCreatedBy(actor.getId());
                    skill.setCreatedAt(OffsetDateTime.now());
                    skill.setUpdatedAt(skill.getCreatedAt());
                    skills().insert(skill);
                    replaceTags(skill, tagNames);
                    append(skill, actor, 0, requestId, content, location);
                    return view(skill, tagNames);
                });
    }

    @Transactional
    public SkillViews.Summary update(
            UserAccount actor, long tenantId, long skillId, SkillInputs.Metadata input) {
        Skill skill = lock(actor, tenantId, skillId);
        if (input == null) {
            throw SkillPackages.invalid("提交参数不能为空");
        }
        if (input.revision() == null || !input.revision().equals(skill.getRevision())) {
            throw conflict("基本信息已被他人修改，请刷新后重试");
        }
        skill.setName(text(input.name(), "名称", 80, false));
        skill.setDescription(text(input.description(), "描述", 1000, true));
        if (input.enabled() == null) {
            throw SkillPackages.invalid("启用状态不能为空");
        }
        skill.setEnabled(input.enabled());
        skill.setRevision(skill.getRevision() + 1);
        skill.setUpdatedAt(OffsetDateTime.now());
        List<String> tagNames = cleanTags(input.tags());
        replaceTags(skill, tagNames);
        if (skills().updateById(skill) != 1) {
            throw conflict("Skill 状态已变化，请重试");
        }
        return view(skill, tagNames);
    }

    public SkillViews.Page<SkillViews.Version> history(
            UserAccount actor, long tenantId, long skillId, int page, int size) {
        require(actor, tenantId, skillId, false);
        Page<SkillVersion> result =
                versions()
                        .selectPage(
                                new Page<>(page, size),
                                Wrappers.<SkillVersion>lambdaQuery()
                                        .eq(SkillVersion::getTenantId, tenantId)
                                        .eq(SkillVersion::getSkillId, skillId)
                                        .orderByDesc(SkillVersion::getNumber));
        return new SkillViews.Page<>(
                result.getRecords().stream().map(this::versionView).toList(),
                result.getTotal(),
                page,
                size);
    }

    public SkillViews.Content content(UserAccount actor, long tenantId, long skillId, int number)
            throws IOException {
        require(actor, tenantId, skillId, false);
        SkillVersion version =
                versions()
                        .selectOne(
                                Wrappers.<SkillVersion>lambdaQuery()
                                        .eq(SkillVersion::getTenantId, tenantId)
                                        .eq(SkillVersion::getSkillId, skillId)
                                        .eq(SkillVersion::getNumber, number));
        if (version == null) {
            throw notFound();
        }
        try (FileDownload download =
                fileStorage()
                        .open(new ObjectLocation(version.getBucket(), version.getObjectKey()))) {
            return new SkillViews.Content(
                    number, SkillPackages.views(SkillPackages.unzip(download.stream())));
        } catch (FileStorageException error) {
            throw unavailable();
        }
    }

    public SkillViews.Version save(
            UserAccount actor,
            long tenantId,
            long skillId,
            SkillInputs.Save input,
            Map<String, byte[]> files)
            throws IOException {
        require(actor, tenantId, skillId, true);
        if (input == null || input.baseVersion() == null || input.baseVersion() < 1) {
            throw SkillPackages.invalid("必须提供有效的编辑基线版本");
        }
        String requestId = requestId(input.requestId());
        PackageData content = packageData(files);
        return uploads.publish(
                tenantId,
                content.archive(),
                location -> {
                    Skill skill = lock(actor, tenantId, skillId);
                    return append(skill, actor, input.baseVersion(), requestId, content, location);
                });
    }

    private SkillViews.Version append(
            Skill skill,
            UserAccount actor,
            int baseVersion,
            String requestId,
            PackageData content,
            ObjectLocation location) {
        SkillVersion existing =
                versions()
                        .selectOne(
                                Wrappers.<SkillVersion>lambdaQuery()
                                        .eq(SkillVersion::getSkillId, skill.getId())
                                        .eq(SkillVersion::getRequestId, requestId));
        if (existing != null) {
            boolean samePayload =
                    existing.getCreatedBy().equals(actor.getId())
                            && existing.getDigest().equals(content.digest())
                            && existing.getNumber() == baseVersion + 1;
            if (!samePayload) {
                throw conflict("重复请求内容不一致");
            }
            return versionView(existing);
        }
        if (baseVersion != skill.getLatestVersion()) {
            throw conflict("内容已更新，旧版本只能查看，请刷新最新版本后编辑");
        }

        SkillVersion version = new SkillVersion();
        version.setTenantId(skill.getTenantId());
        version.setSkillId(skill.getId());
        version.setNumber(baseVersion + 1);
        version.setRequestId(requestId);
        version.setDigest(content.digest());
        version.setBucket(location.bucket());
        version.setObjectKey(location.key());
        version.setFileCount(content.fileCount());
        version.setTotalBytes(content.totalBytes());
        version.setCreatedBy(actor.getId());
        version.setCreatedAt(OffsetDateTime.now());
        versions().insert(version);
        skill.setLatestVersion(version.getNumber());
        skill.setUpdatedAt(version.getCreatedAt());
        if (skills().updateById(skill) != 1) {
            throw conflict("Skill 状态已变化，请重试");
        }
        return versionView(version);
    }

    @Transactional
    public void delete(UserAccount actor, long tenantId, long skillId, long revision) {
        Skill skill = lock(actor, tenantId, skillId);
        if (revision != skill.getRevision()) {
            throw conflict("基本信息已变化，请刷新后删除");
        }
        protectTemplateReference(skillId);
        // 保留版本的对象引用；清理失败仍可由后台重新执行，不产生无记录对象。
        skill.setDeleted(true);
        skill.setRevision(skill.getRevision() + 1);
        if (skills().updateById(skill) != 1) {
            throw conflict("Skill 状态已变化，请重试");
        }
    }

    @Transactional
    public void purge(long tenantId, long skillId) {
        if (skills().lock(tenantId, skillId) == null) {
            return;
        }
        Skill skill = skills().selectById(skillId);
        if (!skill.getDeleted()) {
            return;
        }
        protectTemplateReference(skillId);
        // 每次最多删除 20 个对象；剩余版本保留引用，后续周期继续处理。
        List<SkillVersion> batch =
                versions()
                        .selectPage(
                                new Page<>(1, 20, false),
                                Wrappers.<SkillVersion>lambdaQuery()
                                        .eq(SkillVersion::getSkillId, skillId)
                                        .orderByAsc(SkillVersion::getId))
                        .getRecords();
        for (SkillVersion version : batch) {
            fileStorage().delete(new ObjectLocation(version.getBucket(), version.getObjectKey()));
            versions().deleteById(version.getId());
        }
        if (versions()
                        .selectCount(
                                Wrappers.<SkillVersion>lambdaQuery()
                                        .eq(SkillVersion::getSkillId, skillId))
                > 0) {
            return;
        }
        tags().delete(Wrappers.<SkillTag>lambdaQuery().eq(SkillTag::getSkillId, skillId));
        skills().deleteById(skillId);
    }

    private Skill lock(UserAccount actor, long tenantId, long skillId) {
        identity.tenant(actor, tenantId, false);
        if (skills().lock(tenantId, skillId) == null) {
            throw notFound();
        }
        return require(actor, tenantId, skillId, true);
    }

    private void protectTemplateReference(long skillId) {
        // 已持有 Skill 行锁，与模板绑定写入串行；对象清理开始前拒绝受引用删除。
        if (templateBindings.getObject().selectCount(Wrappers.<TemplateSkill>lambdaQuery()
                .eq(TemplateSkill::getSkillId, skillId)) > 0) {
            throw conflict("该 Skill 已被模板引用，请先移除引用或改为停用");
        }
    }

    private Skill require(UserAccount actor, long tenantId, long skillId, boolean manage) {
        identity.tenant(actor, tenantId, false);
        Skill skill =
                skills().selectOne(
                                Wrappers.<Skill>lambdaQuery()
                                        .eq(Skill::getId, skillId)
                                        .eq(Skill::getTenantId, tenantId)
                                        .eq(Skill::getDeleted, false));
        if (skill == null) {
            throw notFound();
        }
        if (manage && "SYSTEM".equals(skill.getOrigin())) {
            identity.tenant(actor, tenantId, true);
        }
        return skill;
    }

    private SkillViews.Summary view(Skill skill) {
        return view(
                skill,
                tags()
                        .selectList(
                                Wrappers.<SkillTag>lambdaQuery()
                                        .eq(SkillTag::getSkillId, skill.getId())
                                        .orderByAsc(SkillTag::getId))
                        .stream()
                        .map(SkillTag::getName)
                        .toList());
    }

    private SkillViews.Summary view(Skill skill, List<String> tags) {
        return view(
                skill,
                tags,
                versions()
                        .selectOne(
                                Wrappers.<SkillVersion>lambdaQuery()
                                        .eq(SkillVersion::getTenantId, skill.getTenantId())
                                        .eq(SkillVersion::getSkillId, skill.getId())
                                        .eq(SkillVersion::getNumber, skill.getLatestVersion())));
    }

    private SkillViews.Summary view(Skill skill, List<String> tags, SkillVersion latest) {
        if (latest == null) {
            throw conflict("最新内容版本不存在，请刷新后重试");
        }
        return new SkillViews.Summary(
                skill.getId(),
                skill.getSlug(),
                skill.getName(),
                skill.getDescription(),
                tags,
                skill.getOrigin(),
                skill.getEnabled(),
                skill.getLatestVersion(),
                skill.getRevision(),
                skill.getUpdatedAt(),
                latest.getFileCount(),
                latest.getCreatedAt());
    }

    private SkillViews.Version versionView(SkillVersion version) {
        return new SkillViews.Version(
                version.getNumber(),
                version.getFileCount(),
                version.getTotalBytes(),
                version.getCreatedBy(),
                version.getCreatedAt());
    }

    private void replaceTags(Skill skill, List<String> names) {
        tags().delete(Wrappers.<SkillTag>lambdaQuery().eq(SkillTag::getSkillId, skill.getId()));
        for (String name : names) {
            SkillTag tag = new SkillTag();
            tag.setTenantId(skill.getTenantId());
            tag.setSkillId(skill.getId());
            tag.setName(name);
            tags().insert(tag);
        }
    }

    private List<String> cleanTags(List<String> input) {
        if (input == null || input.size() > 20) {
            throw SkillPackages.invalid("必须提供标签列表，最多 20 项");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String name : input) {
            result.add(text(name, "标签", 32, false));
        }
        return new ArrayList<>(result);
    }

    private String text(String value, String label, int maxLength, boolean allowLineBreaks) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw SkillPackages.invalid(label + "不能为空或超长");
        }
        boolean containsControl =
                value.codePoints()
                        .anyMatch(
                                character ->
                                        Character.isISOControl(character)
                                                && !(allowLineBreaks
                                                        && (character == '\n'
                                                                || character == '\r'
                                                                || character == '\t')));
        if (containsControl) {
            throw SkillPackages.invalid(label + "不能包含控制字符");
        }
        return value.trim();
    }

    private PackageData packageData(Map<String, byte[]> files) throws IOException {
        SkillPackages.validate(files);
        byte[] archive = SkillPackages.zip(files);
        return new PackageData(
                archive,
                digest(archive),
                files.size(),
                files.values().stream().mapToLong(value -> value.length).sum());
    }

    private record PackageData(byte[] archive, String digest, int fileCount, long totalBytes) {}

    private String requestId(String value) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (IllegalArgumentException | NullPointerException error) {
            throw SkillPackages.invalid("请求标识必须为标准 UUID");
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private String escape(String q) {
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private FileStorage fileStorage() {
        FileStorage result = storage.getIfAvailable();
        if (result == null) {
            throw unavailable();
        }
        return result;
    }

    private SkillMapper skills() {
        return skillMappers.getObject();
    }

    private SkillTagMapper tags() {
        return tagMappers.getObject();
    }

    private SkillVersionMapper versions() {
        return versionMappers.getObject();
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill 或版本不存在");
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文件存储暂时不可用，请稍后重试");
    }
}
