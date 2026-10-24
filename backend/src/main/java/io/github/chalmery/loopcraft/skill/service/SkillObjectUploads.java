package io.github.chalmery.loopcraft.skill.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.github.chalmery.loopcraft.shared.TraceIdContext;
import io.github.chalmery.loopcraft.skill.mapper.SkillObjectUploadMapper;
import io.github.chalmery.loopcraft.skill.mapper.SkillVersionMapper;
import io.github.chalmery.loopcraft.skill.model.SkillObjectUpload;
import io.github.chalmery.loopcraft.skill.model.SkillVersion;
import io.github.chalmery.loopcraft.storage.model.ObjectLocation;
import io.github.chalmery.loopcraft.storage.service.FileStorage;
import io.github.chalmery.loopcraft.storage.service.FileStorageException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** 先持久化引用，再上传与发布；清理失败保留引用，重启后仍可继续。 */
@Service
public class SkillObjectUploads {
    private static final Logger LOG = LoggerFactory.getLogger(SkillObjectUploads.class);
    private final ObjectProvider<SkillObjectUploadMapper> mappers;
    private final ObjectProvider<SkillVersionMapper> versions;
    private final ObjectProvider<FileStorage> storage;
    private final ObjectProvider<PlatformTransactionManager> managers;
    private final String bucket;

    public SkillObjectUploads(
            ObjectProvider<SkillObjectUploadMapper> mappers,
            ObjectProvider<SkillVersionMapper> versions,
            ObjectProvider<FileStorage> storage,
            ObjectProvider<PlatformTransactionManager> managers,
            @Value("${loopcraft.skill.bucket:loopcraft-skills}") String bucket) {
        this.mappers = mappers;
        this.versions = versions;
        this.storage = storage;
        this.managers = managers;
        this.bucket = bucket;
    }

    public <T> T publish(long tenantId, byte[] archive, Function<ObjectLocation, T> commit)
            throws IOException {
        FileStorage client = storage.getIfAvailable();
        if (client == null) {
            throw unavailable();
        }
        SkillObjectUpload upload =
                transaction()
                        .execute(
                                status -> {
                                    SkillObjectUpload reference = new SkillObjectUpload();
                                    reference.setTenantId(tenantId);
                                    reference.setBucket(bucket);
                                    reference.setObjectKey(
                                            "skills/"
                                                    + tenantId
                                                    + "/uploads/"
                                                    + UUID.randomUUID()
                                                    + ".zip");
                                    reference.setCreatedAt(OffsetDateTime.now());
                                    mappers.getObject().insert(reference);
                                    return reference;
                                });
        try {
            // 上传只持有自己的引用行锁，不占用租户或 Skill 业务锁。
            transaction()
                    .executeWithoutResult(
                            status -> {
                                if (mappers.getObject().lock(upload.getId()) == null) {
                                    throw unavailable();
                                }
                                write(client, upload, archive);
                            });
            return transaction()
                    .execute(
                            status -> {
                                if (mappers.getObject().lock(upload.getId()) == null) {
                                    throw unavailable();
                                }
                                ObjectLocation location =
                                        new ObjectLocation(
                                                upload.getBucket(), upload.getObjectKey());
                                T result = commit.apply(location);
                                // 未引用本次对象的幂等重试仍交给 finally 清理。
                                long attached =
                                        versions.getObject()
                                                .selectCount(
                                                        Wrappers.<SkillVersion>lambdaQuery()
                                                                .eq(
                                                                        SkillVersion::getBucket,
                                                                        upload.getBucket())
                                                                .eq(
                                                                        SkillVersion::getObjectKey,
                                                                        upload.getObjectKey()));
                                if (attached > 0) {
                                    mappers.getObject().deleteById(upload.getId());
                                }
                                return result;
                            });
        } catch (UncheckedIOException error) {
            throw error.getCause();
        } catch (FileStorageException error) {
            throw unavailable();
        } finally {
            // 发布成功时引用已随版本提交删除；其余情况独立清理，失败留待定时任务。
            try {
                discard(upload.getId());
            } catch (RuntimeException error) {
                LOG.warn(
                        "Skill 上传对象清理待重试，uploadId={}，traceId={}，errorType={}",
                        upload.getId(),
                        TraceIdContext.current(),
                        error.getClass().getName());
            }
        }
    }

    public List<SkillObjectUpload> pending() {
        return mappers.getObject()
                .selectPage(
                        new Page<>(1, 20, false),
                        Wrappers.<SkillObjectUpload>lambdaQuery()
                                .lt(
                                        SkillObjectUpload::getCreatedAt,
                                        OffsetDateTime.now().minusMinutes(15))
                                .orderByAsc(
                                        SkillObjectUpload::getCreatedAt, SkillObjectUpload::getId))
                .getRecords();
    }

    public void discard(long uploadId) {
        transaction()
                .executeWithoutResult(
                        status -> {
                            if (mappers.getObject().tryLock(uploadId) == null) {
                                return;
                            }
                            SkillObjectUpload upload = mappers.getObject().selectById(uploadId);
                            long attached =
                                    versions.getObject()
                                            .selectCount(
                                                    Wrappers.<SkillVersion>lambdaQuery()
                                                            .eq(
                                                                    SkillVersion::getBucket,
                                                                    upload.getBucket())
                                                            .eq(
                                                                    SkillVersion::getObjectKey,
                                                                    upload.getObjectKey()));
                            if (attached == 0) {
                                FileStorage client = storage.getIfAvailable();
                                if (client == null) {
                                    throw unavailable();
                                }
                                client.delete(
                                        new ObjectLocation(
                                                upload.getBucket(), upload.getObjectKey()));
                            }
                            mappers.getObject().deleteById(uploadId);
                        });
    }

    private void write(FileStorage client, SkillObjectUpload upload, byte[] archive) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile("loopcraft-skill-", ".zip");
            Files.write(temporary, archive);
            client.upload(
                    new ObjectLocation(upload.getBucket(), upload.getObjectKey()),
                    temporary,
                    "skill.zip",
                    "application/zip");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException error) {
                    LOG.warn(
                            "Skill 临时文件清理失败，uploadId={}，traceId={}，errorType={}",
                            upload.getId(),
                            TraceIdContext.current(),
                            error.getClass().getName());
                }
            }
        }
    }

    private TransactionTemplate transaction() {
        TransactionTemplate transaction = new TransactionTemplate(managers.getObject());
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "文件存储暂时不可用，请稍后重试");
    }
}
