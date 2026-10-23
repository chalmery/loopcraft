package io.github.chalmery.loopcraft.skill;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import io.github.chalmery.loopcraft.identity.model.SessionUser;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.skill.dto.SkillInputs;
import io.github.chalmery.loopcraft.skill.dto.SkillViews;
import io.github.chalmery.loopcraft.skill.service.SkillService;
import io.github.chalmery.loopcraft.skill.service.SkillPackages;
import io.github.chalmery.loopcraft.storage.model.FileDownload;
import io.github.chalmery.loopcraft.storage.model.ObjectLocation;
import io.github.chalmery.loopcraft.storage.model.StoredFile;
import io.github.chalmery.loopcraft.storage.service.FileStorage;
import io.github.chalmery.loopcraft.storage.service.FileStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(SkillIT.StorageConfig.class)
@Sql(scripts={"file:sql/identity/create_user.sql","file:sql/identity/create_tenant.sql","file:sql/identity/create_tenant_member.sql",
        "file:sql/skill/create_skill.sql","file:sql/skill/create_skill_version.sql","file:sql/skill/create_skill_tag.sql"})
@Sql(statements="DROP TABLE IF EXISTS lc_skill_tag, lc_skill_version, lc_skill, lc_tenant_member, lc_tenant, lc_user CASCADE",executionPhase=Sql.ExecutionPhase.AFTER_TEST_METHOD)
class SkillIT {
    @Autowired IdentityService identity;
    @Autowired SkillService skills;
    @Autowired MemoryStorage objects;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    UserAccount admin,owner,member,outsider;
    long tenant,other;
    Map<String,byte[]> files=Map.of("SKILL.md","---\nname: package-name\ndescription: 包内描述\n---\n内容".getBytes(StandardCharsets.UTF_8),"assets/a.bin",new byte[]{0,1,-1});

    @BeforeEach void setup(){
        objects.data.clear();objects.failUpload=false;objects.failDelete=false;
        identity.bootstrap("rooter","integration-password-123");admin=identity.login("rooter","integration-password-123");
        owner=identity.createUser(admin,"owner","integration-password-123","负责人");
        member=identity.createUser(admin,"member","integration-password-123","成员");
        outsider=identity.createUser(admin,"outsider","integration-password-123","其他人");
        tenant=identity.createTenant(admin,"租户一",owner.getId()).getId();other=identity.createTenant(admin,"租户二",outsider.getId()).getId();identity.addMember(owner,tenant,member.getId());
    }
    SkillViews.Summary create() throws Exception{return skills.create(member,tenant,new SkillInputs.Create("test-skill","平台名称","平台描述",List.of("标签"),UUID.randomUUID().toString(),null),files);}
    Authentication auth(UserAccount user){return UsernamePasswordAuthenticationToken.authenticated(new SessionUser(user.getId(),user.getCredentialVersion()),null,List.of());}
    String base(long id){return "/api/tenants/"+tenant+"/skills/"+id;}

    @Test void memberCrudAndMetadataRemainIndependent() throws Exception {
        var s=create();assertThat(s.latestVersion()).isEqualTo(1);
        var updated=skills.update(member,tenant,s.id(),new SkillInputs.Metadata("新平台名称","新平台描述",List.of("新标签","新标签"),false,s.revision()));
        assertThat(updated.tags()).containsExactly("新标签");assertThat(updated.enabled()).isFalse();
        assertThat(skills.content(member,tenant,s.id(),1).files().stream().filter(f->f.path().equals("SKILL.md")).findFirst().orElseThrow().text()).contains("name: package-name").doesNotContain("新平台");
        assertThat(skills.list(member,tenant,1,12,"新标签").total()).isEqualTo(1);
        assertThat(skills.list(member,tenant,1,12,"新平台").total()).isEqualTo(1);
        assertThat(skills.list(member,tenant,1,12,"不存在").total()).isZero();
        assertThatThrownBy(()->skills.update(member,tenant,s.id(),new SkillInputs.Metadata("过期","描述",List.of(),true,s.revision()))).hasMessageContaining("已被他人修改");
        mvc.perform(delete(base(s.id())).param("revision",String.valueOf(updated.revision())).with(authentication(auth(member))).with(csrf())).andExpect(status().isOk());
        assertThat(skills.list(member,tenant,1,12,null).total()).isZero();assertThat(objects.data).hasSize(1);
        objects.failDelete=true;assertThatThrownBy(()->skills.purge(tenant,s.id())).isInstanceOf(FileStorageException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_skill_version",Long.class)).isEqualTo(1);
        objects.failDelete=false;skills.purge(tenant,s.id());assertThat(objects.data).isEmpty();assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_skill",Long.class)).isZero();
    }
    @Test void archivePreviewParsesDefaultsWithoutCreatingRecordsOrObjects() throws Exception {
        MockMultipartFile archive = new MockMultipartFile("archive", "preview.zip", "application/zip", SkillPackages.zip(files));
        String endpoint = "/api/tenants/" + tenant + "/skills/package-preview";
        mvc.perform(multipart(endpoint).file(archive).with(authentication(auth(member))).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.slug").value("package-name"))
                .andExpect(jsonPath("$.data.name").value("package-name"))
                .andExpect(jsonPath("$.data.description").value("包内描述"))
                .andExpect(jsonPath("$.data.files.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_skill", Long.class)).isZero();
        assertThat(objects.data).isEmpty();
        mvc.perform(multipart(endpoint).file(new MockMultipartFile("archive", "bad.zip", "application/zip", new byte[]{1, 2}))
                .with(authentication(auth(member))).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart(endpoint).file(archive).with(authentication(auth(outsider))).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(multipart(endpoint).file(archive).with(authentication(auth(member))))
                .andExpect(status().isForbidden());
    }

    @Test void libraryFiltersAndCountsRemainTenantScopedAcrossPages() throws Exception {
        SkillViews.Summary system = create();
        jdbc.update("UPDATE lc_skill SET origin='SYSTEM' WHERE id=?", system.id());
        SkillViews.Summary custom = skills.create(member, tenant, new SkillInputs.Create("custom-target", "自建目标", "描述",
                List.of("验收"), UUID.randomUUID().toString(), null), files);
        for (int i = 0; i < 12; i++) skills.create(member, tenant, new SkillInputs.Create("custom-" + i, "自建 " + i,
                "分页样例", List.of(), UUID.randomUUID().toString(), null), files);
        skills.create(outsider, other, new SkillInputs.Create("other-tenant", "其他租户", "描述", List.of(),
                UUID.randomUUID().toString(), null), files);
        SkillViews.Summary removed = skills.create(member, tenant, new SkillInputs.Create("removed", "待删除", "描述", List.of(),
                UUID.randomUUID().toString(), null), files);
        skills.delete(member, tenant, removed.id(), removed.revision());
        SkillViews.ListPage all = skills.list(member, tenant, 1, 5, null, null);
        assertThat(all.records()).hasSize(5);
        assertThat(all.total()).isEqualTo(14);
        assertThat(all.counts().custom()).isEqualTo(13);
        assertThat(all.counts().system()).isEqualTo(1);
        SkillViews.ListPage second = skills.list(member, tenant, 2, 5, null, "CUSTOM");
        assertThat(second.records()).hasSize(5).allMatch(item -> item.origin().equals("CUSTOM"));
        assertThat(second.total()).isEqualTo(13);
        assertThat(second.counts()).isEqualTo(all.counts());
        assertThat(skills.list(member, tenant, 1, 5, "不存在", "SYSTEM").counts()).isEqualTo(all.counts());
        Map<String, byte[]> changed = Map.of("SKILL.md", files.get("SKILL.md"), "guide.md", new byte[]{65}, "assets/a.bin", new byte[]{0});
        SkillViews.Version saved = skills.save(member, tenant, custom.id(), new SkillInputs.Save(1, UUID.randomUUID().toString(), null), changed);
        skills.update(member, tenant, custom.id(), new SkillInputs.Metadata("自建目标", "更新描述", List.of(), true, custom.revision()));
        SkillViews.Summary latest = skills.list(member, tenant, 1, 5, "自建目标", "CUSTOM").records().getFirst();
        assertThat(latest.fileCount()).isEqualTo(3);
        assertThat(latest.latestVersion()).isEqualTo(2);
        assertThat(latest.contentUpdatedAt()).isCloseTo(saved.createdAt(),
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MICROS));
        mvc.perform(get("/api/tenants/" + tenant + "/skills").param("origin", "INVALID")
                .with(authentication(auth(member)))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/tenants/" + tenant + "/skills").param("origin", "CUSTOM")
                .with(authentication(auth(outsider)))).andExpect(status().isForbidden());
    }

    @Test void builtinAuthorizationAndTenantScopeAreServerEnforced() throws Exception {
        var s=create();jdbc.update("UPDATE lc_skill SET origin='SYSTEM' WHERE id=?",s.id());
        mvc.perform(get(base(s.id())).with(authentication(auth(member)))).andExpect(status().isOk());
        String payload="{\"name\":\"name\",\"description\":\"description\",\"tags\":[],\"enabled\":true,\"revision\":0}";
        mvc.perform(patch(base(s.id())).contentType("application/json").content(payload).with(authentication(auth(member))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(delete(base(s.id())).param("revision","0").with(authentication(auth(member))).with(csrf())).andExpect(status().isForbidden());
        assertThatThrownBy(()->skills.save(member,tenant,s.id(),new SkillInputs.Save(1,UUID.randomUUID().toString(),null),files)).hasMessageContaining("403");
        mvc.perform(patch(base(s.id())).contentType("application/json").content(payload).with(authentication(auth(owner))).with(csrf())).andExpect(status().isOk());
        mvc.perform(patch(base(s.id())).contentType("application/json").content(payload.replace("\"revision\":0","\"revision\":1")).with(authentication(auth(admin))).with(csrf())).andExpect(status().isOk());
        mvc.perform(get(base(s.id())).with(authentication(auth(outsider)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/tenants/"+other+"/skills/"+s.id()).with(authentication(auth(outsider)))).andExpect(status().isNotFound());
        mvc.perform(get(base(s.id()))).andExpect(status().isUnauthorized());
        mvc.perform(delete(base(s.id())).param("revision","2").with(authentication(auth(owner)))).andExpect(status().isForbidden());
        mvc.perform(get("/api/tenants/"+tenant+"/members").with(authentication(auth(member)))).andExpect(status().isForbidden());
    }
    @Test void retriesConcurrentSavesAppendOnceAndOldVersionsCannotBeEdited() throws Exception {
        var s=create();var request=new SkillInputs.Save(1,UUID.randomUUID().toString(),null);
        Map<String,byte[]> changed=Map.of("SKILL.md","第二版".getBytes(StandardCharsets.UTF_8));
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->skills.save(member,tenant,s.id(),request,changed));var b=pool.submit(()->skills.save(owner,tenant,s.id(),new SkillInputs.Save(1,UUID.randomUUID().toString(),null),changed));
            int success=0;for(var future:List.of(a,b))try{assertThat(future.get(15,TimeUnit.SECONDS).number()).isEqualTo(2);success++;}catch(java.util.concurrent.ExecutionException error){assertThat(error.getCause()).hasMessageContaining("旧版本只能查看");}assertThat(success).isEqualTo(1);
        }
        assertThat(skills.history(member,tenant,s.id(),1,20).total()).isEqualTo(2);
        assertThat(skills.content(member,tenant,s.id(),1).files()).hasSize(2);
        assertThatThrownBy(()->skills.save(member,tenant,s.id(),new SkillInputs.Save(1,UUID.randomUUID().toString(),null),files)).hasMessageContaining("旧版本只能查看");
        var retry=new SkillInputs.Save(2,UUID.randomUUID().toString(),null);assertThat(skills.save(member,tenant,s.id(),retry,changed).number()).isEqualTo(3);assertThat(skills.save(member,tenant,s.id(),retry,changed).number()).isEqualTo(3);
        assertThat(skills.history(member,tenant,s.id(),1,20).total()).isEqualTo(3);
    }
    @Test void failedUploadOrDatabaseCommitLeavesNoVisibleVersion() throws Exception {
        objects.failUpload=true;assertThatThrownBy(this::create).hasMessageContaining("503");assertThat(jdbc.queryForObject("SELECT count(*) FROM lc_skill",Long.class)).isZero();
        objects.failUpload=false;var s=create();jdbc.execute("ALTER TABLE lc_skill_version ADD CONSTRAINT test_fail CHECK (number < 2)");
        assertThatThrownBy(()->skills.save(member,tenant,s.id(),new SkillInputs.Save(1,UUID.randomUUID().toString(),null),files)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(objects.data).hasSize(1);assertThat(skills.detail(member,tenant,s.id()).latestVersion()).isEqualTo(1);
    }
    @Test void creationIsIdempotentAndMultipartKeepsYamlAsPlainFiles() throws Exception {
        String request=UUID.randomUUID().toString();var input=new SkillInputs.Create("first","平台","描述",List.of(),request,null);
        var s=skills.create(member,tenant,input,files);assertThat(skills.create(member,tenant,input,files).id()).isEqualTo(s.id());assertThat(objects.data).hasSize(1);
        String json="{\"slug\":\"upload\",\"name\":\"手填名称\",\"description\":\"手填描述\",\"tags\":[],\"requestId\":\""+UUID.randomUUID()+"\",\"paths\":[\"SKILL.md\"]}";
        mvc.perform(multipart("/api/tenants/"+tenant+"/skills").file(new MockMultipartFile("files","SKILL.md","text/plain",files.get("SKILL.md"))).param("input",json).with(authentication(auth(member))).with(csrf()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.name").value("手填名称")).andExpect(jsonPath("$.data.origin").value("CUSTOM")).andExpect(jsonPath("$.data.latestVersion").value(1));
        mvc.perform(get("/api/tenants/"+tenant+"/skills?size=101").with(authentication(auth(member)))).andExpect(status().isBadRequest());
    }
    @TestConfiguration static class StorageConfig { @Bean MemoryStorage memoryStorage(){return new MemoryStorage();} }
    static class MemoryStorage implements FileStorage {
        final Map<ObjectLocation,byte[]> data=new ConcurrentHashMap<>();boolean failUpload,failDelete;
        public StoredFile upload(ObjectLocation at,Path source,String filename,String type)throws IOException{if(failUpload)throw new FileStorageException(FileStorageException.Reason.UNAVAILABLE);data.put(at,Files.readAllBytes(source));return new StoredFile(at,filename,type,data.get(at).length);}
        public StoredFile stat(ObjectLocation at){return new StoredFile(at,"skill.zip","application/zip",data.get(at).length);}
        public FileDownload open(ObjectLocation at){byte[] bytes=data.get(at);if(bytes==null)throw new FileStorageException(FileStorageException.Reason.NOT_FOUND);return new FileDownload("skill.zip","application/zip",bytes.length,new ByteArrayInputStream(bytes));}
        public void delete(ObjectLocation at){if(failDelete)throw new FileStorageException(FileStorageException.Reason.UNAVAILABLE);data.remove(at);}
        public URI presignDownload(ObjectLocation at){throw new UnsupportedOperationException();}
    }
}
