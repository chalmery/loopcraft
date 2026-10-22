package io.github.chalmery.loopcraft.skill;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import io.github.chalmery.loopcraft.skill.service.SkillPackages;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillPackagesTests {
    @Test
    void preservesYamlBinaryAndNormalizesSingleWrapper() throws Exception {
        byte[] body = "---\nname: other\ndescription: 文件自己的描述\n---\n# 内容".getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = SkillPackages.unzip(new ByteArrayInputStream(SkillPackages.zip(Map.of(
                "wrapper/SKILL.md", body, "wrapper/assets/a.bin", new byte[]{0, 1, -1}))));
        assertThat(files.get("SKILL.md")).containsExactly(body);
        assertThat(files.get("assets/a.bin")).containsExactly((byte) 0, (byte) 1, (byte) -1);
        assertThat(SkillPackages.views(files).stream().filter(f -> f.path().endsWith("bin")).findFirst().orElseThrow().base64()).isEqualTo("AAH/");
        assertThat(SkillPackages.zip(files)).isEqualTo(SkillPackages.zip(files));
    }

    @Test
    void rejectsTraversalDuplicatesMalformedUtf8AndExpansionLimits() throws Exception {
        for (String path : List.of("../SKILL.md", "/SKILL.md", "a/../SKILL.md", "a\\SKILL.md", "a//b", "C:/a")) {
            assertThatThrownBy(() -> SkillPackages.path(path)).hasMessageContaining("400");
        }
        assertThatThrownBy(() -> SkillPackages.input(List.of("SKILL.md", "SKILL.md"), List.of(
                new MockMultipartFile("files", new byte[]{1}), new MockMultipartFile("files", new byte[]{2})), null)).hasMessageContaining("重复");
        assertThatThrownBy(() -> SkillPackages.unzip(new ByteArrayInputStream(SkillPackages.zip(Map.of("SKILL.md", new byte[]{-1}))))).hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> SkillPackages.unzip(new ByteArrayInputStream(SkillPackages.zip(Map.of("SKILL.md", new byte[SkillPackages.MAX_FILE_BYTES + 1]))))).hasMessageContaining("2 MiB");
        assertThatThrownBy(() -> SkillPackages.unzip(new ByteArrayInputStream(new byte[]{1,2,3}))).hasMessageContaining("SKILL.md");
        assertThatThrownBy(() -> SkillPackages.validate(Map.of("SKILL.md", new byte[]{1}, "a", new byte[]{1}, "a/b", new byte[]{1}))).hasMessageContaining("目录冲突");
    }
}
