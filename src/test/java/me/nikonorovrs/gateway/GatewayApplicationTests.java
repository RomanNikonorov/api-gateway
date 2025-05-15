package me.nikonorovrs.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(OAuth2TestConfig.class)
class GatewayApplicationTests {

    @Test
    void contextLoads() {
    }

}