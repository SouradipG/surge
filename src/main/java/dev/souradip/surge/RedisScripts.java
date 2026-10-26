package dev.souradip.surge;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisScripts {

    @Bean
    @SuppressWarnings("rawtypes")
    RedisScript<List> tokenBucketScript() {
        return RedisScript.of(new ClassPathResource("scripts/token_bucket.lua"), List.class);
    }

    @Bean
    RedisScript<Long> reserveScript() {
        return RedisScript.of(new ClassPathResource("scripts/reserve.lua"), Long.class);
    }

    @Bean
    RedisScript<Long> releaseScript() {
        return RedisScript.of(new ClassPathResource("scripts/release.lua"), Long.class);
    }

    @Bean
    RedisScript<Long> reconcileScript() {
        return RedisScript.of(new ClassPathResource("scripts/reconcile.lua"), Long.class);
    }

    @Bean
    RedisScript<Long> joinQueueScript() {
        return RedisScript.of(new ClassPathResource("scripts/join_queue.lua"), Long.class);
    }
}
