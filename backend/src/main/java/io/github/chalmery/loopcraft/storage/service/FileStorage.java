package io.github.chalmery.loopcraft.storage.service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

import io.github.chalmery.loopcraft.storage.model.FileDownload;
import io.github.chalmery.loopcraft.storage.model.ObjectLocation;
import io.github.chalmery.loopcraft.storage.model.StoredFile;

/** 内部通用文件能力；调用业务负责对象命名、权限校验和文件引用的持久化。 */
public interface FileStorage {
    /** 上传本地文件，相同桶和 Key 覆盖原对象；调用期间源文件必须保持不变。 */
    StoredFile upload(ObjectLocation location, Path source, String filename, String contentType) throws IOException;

    /** 查询文件元数据，不读取文件内容。 */
    StoredFile stat(ObjectLocation location);

    /** 流式读取，调用方用 try-with-resources 及时释放连接。 */
    FileDownload open(ObjectLocation location);

    /** 幂等删除，文件不存在时也视为成功。 */
    void delete(ObjectLocation location);

    /** 签发短时下载链接；持有链接者在到期前可直接下载，签发前必须校验业务权限。 */
    URI presignDownload(ObjectLocation location);
}
