package top.enderherman.netdisk;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.serializer.RedisSerializer;
import top.enderherman.netdisk.entity.dto.UserSpaceDto;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeploymentPropertiesTest {
    @Test void redisJsonSerializerRoundTripsTypedQuotaValuesWithoutNarrowingLongs() {
        UserSpaceDto value = new UserSpaceDto();
        value.setUseSpace(42L);
        value.setTotalSpace(1_099_511_627_776L);
        RedisSerializer<Object> serializer = RedisSerializer.json();
        assertEquals(value, serializer.deserialize(serializer.serialize(value)));
    }

    @Test void mailTransportTimeoutsAndRedisPasswordBindFromDeploymentVariables() {
        new ApplicationContextRunner()
                .withUserConfiguration(Binding.class)
                .withPropertyValues("NETDISK_MAIL_CONNECT_TIMEOUT_MS=1234", "NETDISK_MAIL_READ_TIMEOUT_MS=2345",
                        "NETDISK_MAIL_WRITE_TIMEOUT_MS=3456", "NETDISK_REDIS_PASSWORD=test-only-password")
                .withInitializer(context -> {
                    try {
                        for (var source : new YamlPropertySourceLoader().load("netdisk", new ClassPathResource("application.yml"))) {
                            context.getEnvironment().getPropertySources().addLast(source);
                        }
                    } catch (Exception failure) { throw new IllegalStateException(failure); }
                })
                .run(context -> {
                    MailProperties mail = context.getBean(MailProperties.class);
                    for (String transport : new String[]{"smtp", "smtps"}) {
                        assertEquals("1234", mail.getProperties().get("mail." + transport + ".connectiontimeout"));
                        assertEquals("2345", mail.getProperties().get("mail." + transport + ".timeout"));
                        assertEquals("3456", mail.getProperties().get("mail." + transport + ".writetimeout"));
                    }
                    assertEquals("test-only-password", context.getBean(RedisProperties.class).getPassword());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({MailProperties.class, RedisProperties.class})
    static class Binding { }
}
