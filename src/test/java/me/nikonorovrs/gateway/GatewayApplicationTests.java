package me.nikonorovrs.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest
@ContextConfiguration(initializers = RedisTestContainer.class, classes = OAuth2TestConfig.class)

class GatewayApplicationTests {

    @Test
    void contextLoads() {
    }

}