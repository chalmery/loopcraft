package io.github.chalmery.loopcraft.llm.controller;

import java.util.Map;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.llm.dto.LlmRequests;
import io.github.chalmery.loopcraft.llm.dto.LlmViews;
import io.github.chalmery.loopcraft.llm.service.LlmAccessService;
import io.github.chalmery.loopcraft.shared.ApiResponse;
import jakarta.validation.Valid;
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

/** 租户内模型接入，成员只读，OWNER 和平台管理员可管理。 */
@RestController
@RequestMapping("/api/tenants/{tenantId}/llm-sources")
public class LlmAccessController {
    private final IdentityService identity;
    private final LlmAccessService llm;

    public LlmAccessController(IdentityService identity, LlmAccessService llm) {
        this.identity = identity;
        this.llm = llm;
    }

    /** 分页查询当前租户来源。 */
    @GetMapping
    public ApiResponse<LlmViews.Page<LlmViews.Source>> sources(Authentication auth, @PathVariable @Positive long tenantId,
            @RequestParam(defaultValue = "1") @Min(1) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 160) String q) {
        return ApiResponse.success(llm.sources(identity.current(auth), tenantId, page, size, q));
    }

    /** 管理员创建来源，可暂不配置凭证。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LlmViews.Source> create(Authentication auth, @PathVariable @Positive long tenantId,
            @Valid @RequestBody LlmRequests.CreateSource input) {
        return ApiResponse.success(llm.createSource(identity.current(auth), tenantId, input));
    }

    /** 成员可查看连接概况，密钥始终不返回。 */
    @GetMapping("/{sourceId}")
    public ApiResponse<LlmViews.Source> source(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long sourceId) {
        return ApiResponse.success(llm.source(identity.current(auth), tenantId, sourceId));
    }

    /** 管理员局部更新，空密钥保留原值。 */
    @PatchMapping("/{sourceId}")
    public ApiResponse<LlmViews.Source> update(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long sourceId, @RequestBody Map<String, Object> patch) {
        return ApiResponse.success(llm.updateSource(identity.current(auth), tenantId, sourceId, patch));
    }

    /** 管理员事务删除来源及其模型，关联外键失败时全部回滚。 */
    @DeleteMapping("/{sourceId}")
    public ApiResponse<Void> delete(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long sourceId) {
        llm.deleteSource(identity.current(auth), tenantId, sourceId);
        return ApiResponse.success(null);
    }

    /** 成员分页查看连接下的模型。 */
    @GetMapping("/{sourceId}/models")
    public ApiResponse<LlmViews.Page<LlmViews.Model>> models(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long sourceId, @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, @RequestParam(required = false) @Size(max = 160) String q) {
        return ApiResponse.success(llm.models(identity.current(auth), tenantId, sourceId, page, size, q));
    }

    /** 管理员手动添加模型。 */
    @PostMapping("/{sourceId}/models")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LlmViews.Model> createModel(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long sourceId, @Valid @RequestBody LlmRequests.ModelInput input) {
        return ApiResponse.success(llm.createModel(identity.current(auth), tenantId, sourceId, input));
    }

    /** 管理员修改模型名称、实际 ID 或状态。 */
    @PatchMapping("/{sourceId}/models/{modelRecordId}")
    public ApiResponse<LlmViews.Model> updateModel(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long sourceId, @PathVariable @Positive long modelRecordId,
            @RequestBody Map<String, Object> patch) {
        return ApiResponse.success(llm.updateModel(identity.current(auth), tenantId, sourceId, modelRecordId, patch));
    }

    /** 管理员删除无业务引用的模型。 */
    @DeleteMapping("/{sourceId}/models/{modelRecordId}")
    public ApiResponse<Void> deleteModel(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long sourceId, @PathVariable @Positive long modelRecordId) {
        llm.deleteModel(identity.current(auth), tenantId, sourceId, modelRecordId);
        return ApiResponse.success(null);
    }

    /** 管理员查询已保存连接的供应商目录，不写入模型表。 */
    @PostMapping("/{sourceId}/model-discovery")
    public ApiResponse<LlmViews.Discovery> discovery(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long sourceId, @Valid @RequestBody LlmRequests.Discover input) {
        return ApiResponse.success(llm.discover(identity.current(auth), tenantId, sourceId, input));
    }

    /** 管理员只导入勾选项，重复项不覆盖。 */
    @PostMapping("/{sourceId}/models/import")
    public ApiResponse<LlmViews.Imported> importModels(Authentication auth, @PathVariable @Positive long tenantId,
            @PathVariable @Positive long sourceId, @Valid @RequestBody LlmRequests.ImportModels input) {
        return ApiResponse.success(llm.importModels(identity.current(auth), tenantId, sourceId, input));
    }
}
