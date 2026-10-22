package io.github.chalmery.loopcraft.skill.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.chalmery.loopcraft.skill.mapper.SkillMapper;
import io.github.chalmery.loopcraft.skill.model.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 删除失败保留全部对象引用，后续周期重试；不同实例通过行锁串行清理。 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "loopcraft.skill", name = "cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class SkillObjectCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(SkillObjectCleanup.class);
    private final ObjectProvider<SkillMapper> mappers;
    private final SkillService service;
    public SkillObjectCleanup(ObjectProvider<SkillMapper> mappers, SkillService service) { this.mappers = mappers; this.service = service; }

    @Scheduled(initialDelay = 60000, fixedDelay = 60000)
    public void clean() {
        for (Skill skill : mappers.getObject().selectPage(new Page<>(1, 20, false), Wrappers.<Skill>lambdaQuery()
                .eq(Skill::getDeleted, true).orderByAsc(Skill::getId)).getRecords()) {
            try { service.purge(skill.getTenantId(), skill.getId()); }
            catch (RuntimeException error) { LOG.warn("Skill 删除清理待重试，skillId={}", skill.getId()); }
        }
    }
}
