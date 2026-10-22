package io.github.chalmery.loopcraft.storage.service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

import io.github.chalmery.loopcraft.storage.config.StorageProperties;
import io.github.chalmery.loopcraft.storage.model.FileDownload;
import io.github.chalmery.loopcraft.storage.model.ObjectLocation;
import io.github.chalmery.loopcraft.storage.model.StoredFile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/** 将通用文件操作适配到 RustFS S3，不生成对象路径、不管理业务权限或自动建桶。 */
public class RustFsFileStorage implements FileStorage {
    private static final String FILENAME_METADATA = "filename-base64";
    private final S3Client client;
    private final S3Presigner presigner;
    private final StorageProperties properties;

    public RustFsFileStorage(S3Client client, S3Presigner presigner, StorageProperties properties) {
        this.client = client;
        this.presigner = presigner;
        this.properties = properties;
    }

    @Override
    public StoredFile upload(ObjectLocation location, Path source, String filename, String contentType) throws IOException {
        Objects.requireNonNull(location, "对象地址不能为空");
        validateFilename(filename);
        String type = contentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType;
        if (type.length() > 255 || type.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("文件类型格式不合法");
        }
        MediaType.parseMediaType(type);
        long size = Files.size(source);
        if (size > properties.maxFileSize().toBytes()) {
            throw new IllegalArgumentException("文件超过存储大小限制");
        }
        var request = PutObjectRequest.builder().bucket(location.bucket()).key(location.key())
                .contentLength(size).contentType(type).contentDisposition(disposition(filename))
                // S3 元数据头只传 ASCII，保留中文原名用于后续下载。
                .metadata(Map.of(FILENAME_METADATA, Base64.getEncoder().encodeToString(filename.getBytes(StandardCharsets.UTF_8))))
                .build();
        try {
            client.putObject(request, RequestBody.fromFile(source));
            return new StoredFile(location, filename, type, size);
        } catch (SdkException exception) {
            // 桶不存在是部署故障，不能误报为上传成功。
            throw new FileStorageException(FileStorageException.Reason.UNAVAILABLE);
        }
    }

    @Override
    public StoredFile stat(ObjectLocation location) {
        Objects.requireNonNull(location, "对象地址不能为空");
        try {
            var response = client.headObject(HeadObjectRequest.builder()
                    .bucket(location.bucket()).key(location.key()).build());
            return new StoredFile(location, filename(response.metadata(), location),
                    response.contentType(), response.contentLength());
        } catch (SdkException exception) {
            throw readFailure(exception);
        }
    }

    @Override
    public FileDownload open(ObjectLocation location) {
        Objects.requireNonNull(location, "对象地址不能为空");
        var request = GetObjectRequest.builder().bucket(location.bucket()).key(location.key()).build();
        try {
            var stream = client.getObject(request);
            var response = stream.response();
            try {
                return new FileDownload(filename(response.metadata(), location), response.contentType(), response.contentLength(), stream);
            } catch (RuntimeException exception) {
                stream.abort();
                throw exception;
            }
        } catch (SdkException exception) {
            throw readFailure(exception);
        }
    }

    @Override
    public void delete(ObjectLocation location) {
        Objects.requireNonNull(location, "对象地址不能为空");
        var request = DeleteObjectRequest.builder().bucket(location.bucket()).key(location.key()).build();
        try {
            client.deleteObject(request);
        } catch (SdkException exception) {
            throw new FileStorageException(FileStorageException.Reason.UNAVAILABLE);
        }
    }

    @Override
    public URI presignDownload(ObjectLocation location) {
        StoredFile file = stat(location);
        try {
            var request = GetObjectRequest.builder().bucket(location.bucket()).key(location.key())
                    .responseContentDisposition(disposition(file.filename())).build();
            return URI.create(presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(properties.downloadLinkTtl()).getObjectRequest(request).build()).url().toString());
        } catch (SdkException exception) {
            throw new FileStorageException(FileStorageException.Reason.UNAVAILABLE);
        }
    }

    private static void validateFilename(String filename) {
        if (filename == null || filename.isBlank() || filename.length() > 255 || filename.contains("/")
                || filename.contains("\\") || filename.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("文件名格式或长度不合法");
        }
    }

    private static String disposition(String filename) {
        return ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString();
    }

    private static String filename(Map<String, String> metadata, ObjectLocation location) {
        String encoded = metadata.get(FILENAME_METADATA);
        if (encoded == null) {
            // 兼容其他客户端上传的对象，缺少原名时取 Key 的最后一段。
            String fallback = location.key().substring(location.key().lastIndexOf('/') + 1);
            return fallback.isBlank() || fallback.length() > 255 || fallback.contains("\\") ? "download" : fallback;
        }
        try {
            String filename = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            validateFilename(filename);
            return filename;
        } catch (IllegalArgumentException exception) {
            throw new FileStorageException(FileStorageException.Reason.UNAVAILABLE);
        }
    }

    private static FileStorageException readFailure(SdkException exception) {
        boolean missing = exception instanceof S3Exception s3 && s3.statusCode() == 404;
        return new FileStorageException(missing ? FileStorageException.Reason.NOT_FOUND : FileStorageException.Reason.UNAVAILABLE);
    }
}
