package io.github.chalmery.loopcraft.skill.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

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
    private final String bucket;

    public SkillService(IdentityService identity, ObjectProvider<SkillMapper> skillMappers,
            ObjectProvider<SkillTagMapper> tagMappers, ObjectProvider<SkillVersionMapper> versionMappers,
            ObjectProvider<TenantMapper> tenantMappers, ObjectProvider<FileStorage> storage,
            @Value("${loopcraft.skill.bucket:loopcraft-skills}") String bucket) {
        this.identity = identity; this.skillMappers = skillMappers; this.tagMappers = tagMappers;
        this.versionMappers = versionMappers; this.tenantMappers = tenantMappers; this.storage = storage; this.bucket = bucket;
    }

    public SkillViews.Page<SkillViews.Summary> list(UserAccount actor, long tenant, int page, int size, String query) {
        identity.tenant(actor, tenant, false);
        String q = query == null ? "" : query.trim();
        if (q.length() > 160) throw SkillPackages.invalid("搜索条件过长");
        List<Long> tagged = q.isEmpty() ? List.of() : tags().selectList(Wrappers.<SkillTag>lambdaQuery()
                .eq(SkillTag::getTenantId, tenant).like(SkillTag::getName, escape(q))).stream().map(SkillTag::getSkillId).distinct().toList();
        Page<Skill> result = skills().selectPage(new Page<>(page, size), Wrappers.<Skill>lambdaQuery()
                .eq(Skill::getTenantId, tenant).eq(Skill::getDeleted, false)
                .and(!q.isEmpty(), condition -> condition.like(Skill::getName, escape(q)).or().like(Skill::getSlug, escape(q))
                        .or().like(Skill::getDescription, escape(q)).or(!tagged.isEmpty()).in(!tagged.isEmpty(), Skill::getId, tagged))
                .orderByDesc(Skill::getUpdatedAt, Skill::getId));
        List<Long> ids = result.getRecords().stream().map(Skill::getId).toList();
        Map<Long, List<SkillTag>> grouped = ids.isEmpty() ? Map.of() : tags().selectList(Wrappers.<SkillTag>lambdaQuery()
                .eq(SkillTag::getTenantId, tenant).in(SkillTag::getSkillId, ids).orderByAsc(SkillTag::getId))
                .stream().collect(Collectors.groupingBy(SkillTag::getSkillId));
        return new SkillViews.Page<>(result.getRecords().stream().map(s -> view(s, grouped.getOrDefault(s.getId(), List.of())
                .stream().map(SkillTag::getName).toList())).toList(), result.getTotal(), page, size);
    }

    public SkillViews.Summary detail(UserAccount actor, long tenant, long id) { return view(require(actor, tenant, id, false)); }

    @Transactional(rollbackFor = IOException.class)
    public SkillViews.Summary create(UserAccount actor, long tenant, SkillInputs.Create input, Map<String, byte[]> files) throws IOException {
        identity.tenant(actor, tenant, false);
        // 复用租户行锁，使创建重试和标识唯一性检查在多实例下保持一致。
        tenantMappers.getObject().lockById(tenant);
        identity.tenant(actor, tenant, false);
        String request = requestId(input.requestId());
        Skill existing = skills().selectOne(Wrappers.<Skill>lambdaQuery().eq(Skill::getTenantId, tenant).eq(Skill::getCreationRequest, request));
        if (existing != null) {
            if (existing.getDeleted() || !existing.getCreatedBy().equals(actor.getId())) throw conflict("创建请求标识已使用");
            SkillVersion saved = versions().selectOne(Wrappers.<SkillVersion>lambdaQuery().eq(SkillVersion::getSkillId, existing.getId()).eq(SkillVersion::getNumber, 1));
            if (!existing.getSlug().equals(input.slug()) || !saved.getDigest().equals(digest(SkillPackages.zip(files)))) throw conflict("重复请求内容不一致");
            return view(existing);
        }
        String slug = text(input.slug(), "Skill 标识", 64);
        if (!slug.matches("[a-z0-9]+(-[a-z0-9]+)*")) throw SkillPackages.invalid("标识仅允许小写字母、数字和连接符");
        if (skills().selectCount(Wrappers.<Skill>lambdaQuery().eq(Skill::getTenantId, tenant).eq(Skill::getSlug, slug).eq(Skill::getDeleted, false)) > 0) throw conflict("当前租户已有相同标识");
        Skill skill = new Skill();
        skill.setTenantId(tenant); skill.setSlug(slug); skill.setName(text(input.name(), "名称", 80));
        skill.setDescription(text(input.description(), "描述", 1000)); skill.setOrigin("CUSTOM");
        skill.setEnabled(true); skill.setDeleted(false); skill.setLatestVersion(0); skill.setRevision(0L);
        skill.setCreationRequest(request); skill.setCreatedBy(actor.getId()); skill.setCreatedAt(OffsetDateTime.now()); skill.setUpdatedAt(skill.getCreatedAt());
        List<String> tagNames = cleanTags(input.tags());
        skills().insert(skill); replaceTags(skill, tagNames);
        append(skill, actor, 0, request, files);
        return view(skill, tagNames);
    }

    @Transactional
    public SkillViews.Summary update(UserAccount actor, long tenant, long id, SkillInputs.Metadata input) {
        Skill skill = lock(actor, tenant, id);
        if (input.revision() == null || !input.revision().equals(skill.getRevision())) throw conflict("基本信息已被他人修改，请刷新后重试");
        skill.setName(text(input.name(), "名称", 80)); skill.setDescription(text(input.description(), "描述", 1000));
        if (input.enabled() == null) throw SkillPackages.invalid("启用状态不能为空");
        skill.setEnabled(input.enabled()); skill.setRevision(skill.getRevision() + 1); skill.setUpdatedAt(OffsetDateTime.now());
        List<String> tagNames = cleanTags(input.tags()); replaceTags(skill, tagNames); skills().updateById(skill);
        return view(skill, tagNames);
    }

    public SkillViews.Page<SkillViews.Version> history(UserAccount actor, long tenant, long id, int page, int size) {
        require(actor, tenant, id, false);
        Page<SkillVersion> result = versions().selectPage(new Page<>(page, size), Wrappers.<SkillVersion>lambdaQuery()
                .eq(SkillVersion::getTenantId, tenant).eq(SkillVersion::getSkillId, id).orderByDesc(SkillVersion::getNumber));
        return new SkillViews.Page<>(result.getRecords().stream().map(this::versionView).toList(), result.getTotal(), page, size);
    }

    public SkillViews.Content content(UserAccount actor, long tenant, long id, int number) throws IOException {
        require(actor, tenant, id, false);
        SkillVersion version = versions().selectOne(Wrappers.<SkillVersion>lambdaQuery().eq(SkillVersion::getTenantId, tenant)
                .eq(SkillVersion::getSkillId, id).eq(SkillVersion::getNumber, number));
        if (version == null) throw notFound();
        try (FileDownload download = fileStorage().open(new ObjectLocation(version.getBucket(), version.getObjectKey()))) {
            return new SkillViews.Content(number, SkillPackages.views(SkillPackages.unzip(download.stream())));
        } catch (FileStorageException error) { throw unavailable(); }
    }

    @Transactional(rollbackFor = IOException.class)
    public SkillViews.Version save(UserAccount actor, long tenant, long id, SkillInputs.Save input, Map<String, byte[]> files) throws IOException {
        Skill skill = lock(actor, tenant, id);
        if (input.baseVersion() == null) throw SkillPackages.invalid("必须提供编辑基线版本");
        return append(skill, actor, input.baseVersion(), requestId(input.requestId()), files);
    }

    private SkillViews.Version append(Skill skill, UserAccount actor, int base, String request, Map<String, byte[]> files) throws IOException {
        byte[] archive = SkillPackages.zip(SkillPackages.validate(files));
        String hash = digest(archive);
        SkillVersion existing = versions().selectOne(Wrappers.<SkillVersion>lambdaQuery().eq(SkillVersion::getSkillId, skill.getId()).eq(SkillVersion::getRequestId, request));
        if (existing != null) {
            if (!existing.getCreatedBy().equals(actor.getId()) || !existing.getDigest().equals(hash)) throw conflict("重复请求内容不一致");
            return versionView(existing);
        }
        if (base != skill.getLatestVersion()) throw conflict("内容已更新，旧版本只能查看，请刷新最新版本后编辑");
        ObjectLocation location = new ObjectLocation(bucket, "skills/" + skill.getTenantId() + "/" + skill.getId() + "/" + UUID.randomUUID() + ".zip");
        FileStorage client = fileStorage();
        // 回滚时尽力删除新对象；失败仅记录业务 ID，不输出 SDK 异常或凭证。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) try { client.delete(location); }
                catch (RuntimeException error) { LOG.warn("Skill 回滚对象清理失败，skillId={}", skill.getId()); }
            }
        });
        Path temp = Files.createTempFile("loopcraft-skill-", ".zip");
        try {
            Files.write(temp, archive);
            client.upload(location, temp, "skill.zip", "application/zip");
        } catch (FileStorageException error) { throw unavailable(); }
        finally { Files.deleteIfExists(temp); }
        SkillVersion version = new SkillVersion();
        version.setTenantId(skill.getTenantId()); version.setSkillId(skill.getId()); version.setNumber(base + 1);
        version.setRequestId(request); version.setDigest(hash); version.setBucket(location.bucket()); version.setObjectKey(location.key());
        version.setFileCount(files.size()); version.setTotalBytes(files.values().stream().mapToLong(bytes -> bytes.length).sum());
        version.setCreatedBy(actor.getId()); version.setCreatedAt(OffsetDateTime.now()); versions().insert(version);
        skill.setLatestVersion(version.getNumber()); skill.setUpdatedAt(version.getCreatedAt()); skills().updateById(skill);
        return versionView(version);
    }

    @Transactional
    public void delete(UserAccount actor, long tenant, long id, long revision) {
        Skill skill = lock(actor, tenant, id);
        if (revision != skill.getRevision()) throw conflict("基本信息已变化，请刷新后删除");
        // 保留版本的对象引用；清理失败仍可由后台重新执行，不产生无记录对象。
        skill.setDeleted(true); skill.setRevision(skill.getRevision() + 1); skills().updateById(skill);
    }

    @Transactional
    public void purge(long tenant, long id) {
        if (skills().lock(tenant, id) == null) return;
        Skill skill = skills().selectById(id);
        if (!skill.getDeleted()) return;
        for (SkillVersion version : versions().selectList(Wrappers.<SkillVersion>lambdaQuery().eq(SkillVersion::getSkillId, id))) {
            fileStorage().delete(new ObjectLocation(version.getBucket(), version.getObjectKey()));
        }
        versions().delete(Wrappers.<SkillVersion>lambdaQuery().eq(SkillVersion::getSkillId, id));
        tags().delete(Wrappers.<SkillTag>lambdaQuery().eq(SkillTag::getSkillId, id)); skills().deleteById(id);
    }

    private Skill lock(UserAccount actor, long tenant, long id) {
        identity.tenant(actor, tenant, false);
        if (skills().lock(tenant, id) == null) throw notFound();
        return require(actor, tenant, id, true);
    }

    private Skill require(UserAccount actor, long tenant, long id, boolean manage) {
        identity.tenant(actor, tenant, false);
        Skill skill = skills().selectOne(Wrappers.<Skill>lambdaQuery().eq(Skill::getId, id).eq(Skill::getTenantId, tenant).eq(Skill::getDeleted, false));
        if (skill == null) throw notFound();
        if (manage && "SYSTEM".equals(skill.getOrigin())) identity.tenant(actor, tenant, true);
        return skill;
    }

    private SkillViews.Summary view(Skill skill) {
        return view(skill, tags().selectList(Wrappers.<SkillTag>lambdaQuery().eq(SkillTag::getSkillId, skill.getId())
                .orderByAsc(SkillTag::getId)).stream().map(SkillTag::getName).toList());
    }
    private SkillViews.Summary view(Skill skill, List<String> tags) {
        return new SkillViews.Summary(skill.getId(), skill.getSlug(), skill.getName(), skill.getDescription(), tags,
                skill.getOrigin(), skill.getEnabled(), skill.getLatestVersion(), skill.getRevision(), skill.getUpdatedAt());
    }
    private SkillViews.Version versionView(SkillVersion version) {
        return new SkillViews.Version(version.getNumber(), version.getFileCount(), version.getTotalBytes(), version.getCreatedBy(), version.getCreatedAt());
    }
    private void replaceTags(Skill skill, List<String> names) {
        tags().delete(Wrappers.<SkillTag>lambdaQuery().eq(SkillTag::getSkillId, skill.getId()));
        for (String name : names) { SkillTag tag = new SkillTag(); tag.setTenantId(skill.getTenantId()); tag.setSkillId(skill.getId()); tag.setName(name); tags().insert(tag); }
    }
    private List<String> cleanTags(List<String> input) {
        if (input == null || input.size() > 20) throw SkillPackages.invalid("必须提供标签列表，最多 20 项");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String name : input) result.add(text(name, "标签", 32));
        return new ArrayList<>(result);
    }
    private String text(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max || (label.equals("描述") ? value.replace("\n", "").replace("\r", "").replace("\t", "") : value).codePoints().anyMatch(Character::isISOControl)) throw SkillPackages.invalid(label + "不能为空、包含控制字符或超长");
        return value.trim();
    }
    private String requestId(String value) {
        try { if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException(); return value; }
        catch (IllegalArgumentException | NullPointerException error) { throw SkillPackages.invalid("请求标识必须为标准 UUID"); }
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private String escape(String q) { return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
    private FileStorage fileStorage() { FileStorage result = storage.getIfAvailable(); if (result == null) throw unavailable(); return result; }
    private SkillMapper skills() { return skillMappers.getObject(); }
    private SkillTagMapper tags() { return tagMappers.getObject(); }
    private SkillVersionMapper versions() { return versionMappers.getObject(); }
    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill 或版本不存在"); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文件存储暂时不可用，请稍后重试"); }
}
