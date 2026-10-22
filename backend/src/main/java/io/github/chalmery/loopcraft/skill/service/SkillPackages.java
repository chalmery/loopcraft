package io.github.chalmery.loopcraft.skill.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import io.github.chalmery.loopcraft.skill.dto.SkillViews;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** 包仅作为字节处理，不解压到磁盘，不解析 YAML，不执行脚本。 */
public final class SkillPackages {
    public static final int MAX_FILES = 200;
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_ARCHIVE_BYTES = 20 * 1024 * 1024;

    private SkillPackages() { }

    public static Map<String, byte[]> input(List<String> paths, List<MultipartFile> files, MultipartFile archive) throws IOException {
        if (archive != null && !archive.isEmpty()) {
            if ((paths != null && !paths.isEmpty()) || (files != null && !files.isEmpty())) throw invalid("压缩包与文件列表不能同时提交");
            if (archive.getSize() > MAX_ARCHIVE_BYTES) throw invalid("压缩包不能超过 20 MiB");
            try (InputStream stream = archive.getInputStream()) { return unzip(stream); }
        }
        if (paths == null || files == null || paths.size() != files.size() || paths.size() > MAX_FILES) {
            throw invalid("文件路径与上传文件数量不匹配，最多 200 个文件");
        }
        Map<String, byte[]> result = new TreeMap<>();
        int total = 0;
        for (int i = 0; i < paths.size(); i++) {
            String path = path(paths.get(i));
            try (InputStream stream = files.get(i).getInputStream()) {
                byte[] bytes = limited(stream, MAX_FILE_BYTES);
                total += bytes.length;
                if (total > MAX_BYTES) throw invalid("包内容不能超过 10 MiB");
                if (result.putIfAbsent(path, bytes) != null) throw invalid("文件路径不能重复");
            }
        }
        return validate(result);
    }

    public static Map<String, byte[]> unzip(InputStream input) throws IOException {
        Map<String, byte[]> result = new TreeMap<>();
        int total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_FILES * 4) throw invalid("压缩包目录条目过多");
                if (entry.isDirectory()) { path(entry.getName().replaceFirst("/$", "")); if (zip.read() != -1) throw invalid("目录条目不能包含文件内容"); continue; }
                String name = path(entry.getName());
                byte[] bytes = limited(zip, MAX_FILE_BYTES);
                total += bytes.length;
                if (total > MAX_BYTES || result.size() >= MAX_FILES) throw invalid("包最多 200 个文件、解压后最多 10 MiB");
                if (result.putIfAbsent(name, bytes) != null) throw invalid("压缩包中存在重复文件路径");
                zip.closeEntry();
            }
        } catch (java.util.zip.ZipException | IllegalArgumentException error) {
            throw invalid("无法读取 ZIP，请检查编码、加密或文件完整性");
        }
        // 兼容常见的单层包目录；不重写任何文件内容。
        if (!result.containsKey("SKILL.md") && !result.isEmpty()) {
            String first = result.keySet().iterator().next();
            int slash = first.indexOf('/');
            if (slash > 0) {
                String prefix = first.substring(0, slash + 1);
                if (result.keySet().stream().allMatch(key -> key.startsWith(prefix))) {
                    Map<String, byte[]> normalized = new TreeMap<>();
                    result.forEach((key, value) -> normalized.put(key.substring(prefix.length()), value));
                    result = normalized;
                }
            }
        }
        return validate(result);
    }

    public static Map<String, byte[]> validate(Map<String, byte[]> files) {
        if (files.size() > MAX_FILES || files.values().stream().mapToLong(b -> b.length).sum() > MAX_BYTES) throw invalid("包最多 200 个文件、解压后最多 10 MiB");
        files.forEach((name, bytes) -> { path(name); if (bytes.length > MAX_FILE_BYTES) throw invalid("单文件不能超过 2 MiB"); });
        if (files.isEmpty() || !files.containsKey("SKILL.md") || files.get("SKILL.md").length == 0) throw invalid("包根目录必须包含非空 SKILL.md");
        if (text(files.get("SKILL.md")) == null) throw invalid("SKILL.md 必须是 UTF-8 文本");
        for (String name : files.keySet()) {
            int slash = name.indexOf('/');
            while (slash > 0) {
                if (files.containsKey(name.substring(0, slash))) throw invalid("文件路径与目录冲突");
                slash = name.indexOf('/', slash + 1);
            }
        }
        return files;
    }

    public static String path(String value) {
        if (value == null || value.isBlank() || value.length() > 240 || value.startsWith("/")
                || value.contains("\\") || value.contains(":") || value.codePoints().anyMatch(Character::isISOControl)) throw invalid("文件路径必须是包内相对路径，最长 240 字符");
        for (String part : value.split("/", -1)) if (part.isBlank() || part.equals(".") || part.equals("..")) throw invalid("文件路径不能包含空段、. 或 ..");
        return value;
    }

    public static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> file : new TreeMap<>(files).entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    public static List<SkillViews.File> views(Map<String, byte[]> files) {
        List<SkillViews.File> result = new ArrayList<>();
        files.forEach((path, bytes) -> {
            String text = text(bytes);
            result.add(new SkillViews.File(path, bytes.length, text, text == null ? Base64.getEncoder().encodeToString(bytes) : null));
        });
        return result;
    }

    private static String text(byte[] bytes) {
        for (byte b : bytes) if (b == 0) return null;
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException error) { return null; }
    }

    private static byte[] limited(InputStream input, int limit) throws IOException {
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) throw invalid("单文件不能超过 2 MiB");
        return bytes;
    }

    public static ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
