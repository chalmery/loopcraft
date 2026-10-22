package io.github.chalmery.loopcraft.storage.model;

import java.io.IOException;
import java.io.InputStream;

/** 文件信息和下载流；关闭此对象同时释放底层 HTTP 连接。 */
public record FileDownload(String filename, String contentType, long size, InputStream stream) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        stream.close();
    }
}
