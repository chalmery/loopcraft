package io.github.chalmery.loopcraft;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LoopCraftBackendApplicationTests {
    @Autowired
    private ApplicationContext context;

    @Test
    void startsWithoutDatabase() {
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
    }
}
