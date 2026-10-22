package io.github.chalmery.loopcraft.skill.controller;

import java.io.IOException;
import java.util.List;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.shared.ApiResponse;
import io.github.chalmery.loopcraft.skill.dto.SkillInputs;
import io.github.chalmery.loopcraft.skill.dto.SkillViews;
import io.github.chalmery.loopcraft.skill.service.SkillPackages;
import io.github.chalmery.loopcraft.skill.service.SkillService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Skill 管理接口；文件只通过已授权的租户资源访问，沿用 Session 与 CSRF。 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/skills")
public class SkillController {
    private final IdentityService identity;
    private final SkillService skills;
    private final ObjectMapper json;

    public SkillController(IdentityService identity, SkillService skills, ObjectMapper json) {
        this.identity = identity; this.skills = skills; this.json = json;
    }

    /** 所有租户成员可搜索、分页查看自建和内置 Skill。 */
    @GetMapping
    public ApiResponse<SkillViews.Page<SkillViews.Summary>> list(Authentication auth, @PathVariable @Positive long tenantId,
            @RequestParam(defaultValue = "1") @Min(1) int page, @RequestParam(defaultValue = "12") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 160) String q) {
        return ApiResponse.success(skills.list(identity.current(auth), tenantId, page, size, q));
    }

    /** 首次保存同时创建平台记录与 v1；包内字段不回填基本信息。 */
    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SkillViews.Summary> create(Authentication auth, @PathVariable @Positive long tenantId,
            @RequestParam String input, @RequestParam(required = false) List<MultipartFile> files,
            @RequestParam(required = false) MultipartFile archive) throws IOException {
        var actor = identity.current(auth);
        identity.tenant(actor, tenantId, false);
        SkillInputs.Create data = decode(input, SkillInputs.Create.class);
        return ApiResponse.success(skills.create(actor, tenantId, data, SkillPackages.input(data.paths(), files, archive)));
    }

    /** 读取基本信息，不返回对象存储地址。 */
    @GetMapping("/{id}")
    public ApiResponse<SkillViews.Summary> detail(Authentication auth, @PathVariable @Positive long tenantId, @PathVariable @Positive long id) {
        return ApiResponse.success(skills.detail(identity.current(auth), tenantId, id));
    }

    /** 自建由成员管理；内置由 OWNER 或平台管理员管理。 */
    @PatchMapping("/{id}")
    public ApiResponse<SkillViews.Summary> update(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long id, @RequestBody SkillInputs.Metadata input) {
        return ApiResponse.success(skills.update(identity.current(auth), tenantId, id, input));
    }

    /** 删除后隐藏，后台按保留的引用幂等清理对象。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(Authentication auth, @PathVariable @Positive long tenantId, @PathVariable @Positive long id,
            @RequestParam @Min(0) long revision) {
        skills.delete(identity.current(auth), tenantId, id, revision);
        return ApiResponse.success(null);
    }

    /** 版本历史仅提供查看。 */
    @GetMapping("/{id}/versions")
    public ApiResponse<SkillViews.Page<SkillViews.Version>> versions(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long id, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(skills.history(identity.current(auth), tenantId, id, page, size));
    }

    /** 按版本读取完整文件集合；二进制以 base64 返回，不在浏览器执行。 */
    @GetMapping("/{id}/versions/{number}")
    public ApiResponse<SkillViews.Content> content(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long id, @PathVariable @Min(1) int number) throws IOException {
        return ApiResponse.success(skills.content(identity.current(auth), tenantId, id, number));
    }

    /** 校验最新基线并追加版本，不允许从旧版本发起恢复。 */
    @PostMapping(path = "/{id}/versions", consumes = "multipart/form-data")
    public ApiResponse<SkillViews.Version> save(Authentication auth, @PathVariable @Positive long tenantId, @PathVariable @Positive long id,
            @RequestParam String input, @RequestParam(required = false) List<MultipartFile> files,
            @RequestParam(required = false) MultipartFile archive) throws IOException {
        var actor = identity.current(auth);
        // 先校验资源可见性，再处理上传；最终写权限仍由服务在锁内校验。
        skills.detail(actor, tenantId, id);
        SkillInputs.Save data = decode(input, SkillInputs.Save.class);
        return ApiResponse.success(skills.save(actor, tenantId, id, data, SkillPackages.input(data.paths(), files, archive)));
    }

    /** 上传包预览不落盘、不创建版本，也不从文件内容解析平台字段。 */
    @PostMapping(path = "/package-preview", consumes = "multipart/form-data")
    public ApiResponse<SkillViews.Content> preview(Authentication auth, @PathVariable @Positive long tenantId,
            @RequestParam MultipartFile archive) throws IOException {
        identity.tenant(identity.current(auth), tenantId, false);
        return ApiResponse.success(new SkillViews.Content(0, SkillPackages.views(SkillPackages.input(null, null, archive))));
    }

    private <T> T decode(String input, Class<T> type) {
        if (input.length() > 100_000) throw SkillPackages.invalid("提交参数过长");
        try { T value = json.readValue(input, type); if (value == null) throw SkillPackages.invalid("提交参数不能为空"); return value; }
        catch (JacksonException error) { throw SkillPackages.invalid("提交参数格式不正确"); }
    }
}
