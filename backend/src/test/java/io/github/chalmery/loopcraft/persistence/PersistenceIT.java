package io.github.chalmery.loopcraft.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@MapperScan(basePackageClasses = ProbeMapper.class)
@Transactional
@Sql("/sql/persistence-probe.sql")
class PersistenceIT {

    @Autowired
    private ProbeMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void supportsBaseMapperCrudAndPostgresJsonb() {
        PersistenceProbe probe = new PersistenceProbe();
        probe.setName("before");
        assertThat(mapper.insert(probe)).isEqualTo(1);
        assertThat(probe.getId()).isNotNull();
        assertThat(mapper.selectById(probe.getId()).getName()).isEqualTo("before");

        probe.setName("after");
        assertThat(mapper.updateById(probe)).isEqualTo(1);
        assertThat(mapper.selectList(Wrappers.<PersistenceProbe>lambdaQuery()
                .eq(PersistenceProbe::getName, "after"))).hasSize(1);

        jdbc.update("UPDATE loopcraft_persistence_probe SET metadata = ?::jsonb WHERE id = ?",
                "{\"source\":\"integration\"}", probe.getId());
        assertThat(jdbc.queryForObject(
                "SELECT metadata ->> 'source' FROM loopcraft_persistence_probe WHERE id = ?",
                String.class, probe.getId())).isEqualTo("integration");

        assertThat(mapper.deleteById(probe.getId())).isEqualTo(1);
        assertThat(mapper.selectById(probe.getId())).isNull();
    }
}
