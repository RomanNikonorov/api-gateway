package me.nikonorovrs.gateway;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public class RedisTestContainer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final GenericContainer<?> redisContainer = new GenericContainer<>(DockerImageName.parse("redis:latest"))
            .withExposedPorts(6379);

    static {
        redisContainer.start();
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        TestPropertyValues values = TestPropertyValues.of(
                "spring.data.redis.host=" + redisContainer.getHost(),
                "spring.data.redis.port=" + redisContainer.getMappedPort(6379)
        );
        values.applyTo(applicationContext);
    }
}