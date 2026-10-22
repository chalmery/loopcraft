package io.github.chalmery.loopcraft.storage.service;

/** 仅暴露稳定错误分类，不向上层传播可能含签名、路径或凭证的 SDK 异常文本。 */
public class FileStorageException extends RuntimeException {
    public enum Reason { NOT_FOUND, UNAVAILABLE }

    private final Reason reason;

    public FileStorageException(Reason reason) {
        super(reason == Reason.NOT_FOUND ? "文件不存在" : "文件存储暂时不可用");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
