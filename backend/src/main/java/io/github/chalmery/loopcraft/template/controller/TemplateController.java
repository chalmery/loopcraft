package io.github.chalmery.loopcraft.template.controller;

import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.shared.ApiResponse;
import io.github.chalmery.loopcraft.template.dto.TemplateInput;
import io.github.chalmery.loopcraft.template.dto.TemplateViews;
import io.github.chalmery.loopcraft.template.service.TemplateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前租户成员共享维护模板，平台身份不替代成员身份。 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/agent-templates")
public class TemplateController {
    private final IdentityService identity;
    private final TemplateService templates;

    public TemplateController(IdentityService identity, TemplateService templates) {
        this.identity = identity;
        this.templates = templates;
    }

    /** 分页查询模板与相同搜索范围的启停计数。 */
    @GetMapping
    public ApiResponse<TemplateViews.ListPage> list(Authentication auth, @PathVariable @Positive long tenantId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 160) String q,
            @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.success(templates.list(identity.current(auth), tenantId, page, size, q, enabled));
    }

    /** 分页选择同租户模型，包含维护旧引用所需的停用状态。 */
    @GetMapping("/models")
    public ApiResponse<TemplateViews.Page<TemplateViews.ModelChoice>> models(Authentication auth,
            @PathVariable @Positive long tenantId, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 160) String q) {
        return ApiResponse.success(templates.modelChoices(identity.current(auth), tenantId, page, size, q));
    }

    /** 读取配置与依赖可用性，不读取凭证。 */
    @GetMapping("/{id}")
    public ApiResponse<TemplateViews.Detail> detail(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long id) {
        return ApiResponse.success(templates.detail(identity.current(auth), tenantId, id));
    }

    /** 建议未占用的副本名称，实际保存仍由唯一约束兜底。 */
    @GetMapping("/{id}/copy-name")
    public ApiResponse<String> copyName(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long id) {
        return ApiResponse.success(templates.copyName(identity.current(auth), tenantId, id));
    }

    /** 原子创建配置与固定版本绑定，支持请求标识重试。 */
    @PostMapping
    public ApiResponse<TemplateViews.Detail> create(Authentication auth, @PathVariable @Positive long tenantId,
            @Valid @RequestBody TemplateInput input) {
        return ApiResponse.success(templates.save(identity.current(auth), tenantId, null, input));
    }

    /** 完整替换配置，修订冲突不覆盖其他成员修改。 */
    @PutMapping("/{id}")
    public ApiResponse<TemplateViews.Detail> update(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long id, @Valid @RequestBody TemplateInput input) {
        return ApiResponse.success(templates.save(identity.current(auth), tenantId, id, input));
    }

    /** 删除模板和绑定，不删除其模型或 Skill。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long id, @RequestParam @PositiveOrZero long baseRevision) {
        templates.delete(identity.current(auth), tenantId, id, baseRevision);
        return ApiResponse.success(null);
    }
}
