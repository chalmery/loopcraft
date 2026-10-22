package io.github.chalmery.loopcraft.storage.model;

/** 对象地址及文件元数据；size 为字节数，不包含业务归属或存储凭证。 */
public record StoredFile(ObjectLocation location, String filename, String contentType, long size) {
}
