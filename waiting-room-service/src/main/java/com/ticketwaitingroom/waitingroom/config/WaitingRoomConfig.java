package com.ticketwaitingroom.waitingroom.config;

import com.ticketwaitingroom.waitingroom.queue.WaitingRoomQueue;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.util.List;

/**
 * Wires the waiting-room beans. The Redis connection itself comes from Spring Boot's
 * auto-configuration off {@code spring.data.redis.*}, so the same code points at a local
 * container or at the ElastiCache primary endpoint purely by configuration.
 *
 * <p>The Lua scripts are loaded once here and reused. Spring Data Redis sends them by
 * SHA ({@code EVALSHA}) and only falls back to shipping the source when the server has
 * not seen them yet, so the script body is not on the wire per request.
 */
@Configuration
@EnableConfigurationProperties(WaitingRoomProperties.class)
public class WaitingRoomConfig {

    @Bean
    public WaitingRoomQueue waitingRoomQueue(StringRedisTemplate redisTemplate, WaitingRoomProperties properties) {
        return new WaitingRoomQueue(
                redisTemplate,
                listScript("redis/join.lua"),
                listScript("redis/position.lua"),
                listScript("redis/admit.lua"),
                properties.keyTtl().toSeconds());
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> listScript(String location) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(List.class);
        return script;
    }
}
