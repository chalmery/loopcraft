package io.github.chalmery.loopcraft;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LoopCraftBackendApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void startsWithoutModelCredentialsOrModelBeans() {
        assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
    }

}
