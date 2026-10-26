package dev.souradip.surge.waitingroom;

import dev.souradip.surge.RedisKeys;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class AdmissionTicker {

    private static final Logger log = LoggerFactory.getLogger(AdmissionTicker.class);

    private final StringRedisTemplate redis;
    private final WaitingRoom room;

    AdmissionTicker(StringRedisTemplate redis, WaitingRoom room) {
        this.redis = redis;
        this.room = room;
    }

    @Scheduled(fixedDelayString = "${surge.waiting-room.tick}")
    void tick() {
        try {
            Set<String> events = redis.opsForSet().members(RedisKeys.ACTIVE_EVENTS);
            if (events != null) {
                events.forEach(id -> room.admitNextBatch(Long.parseLong(id)));
            }
        } catch (RuntimeException e) {
            log.warn("admission tick failed: {}", e.toString());
        }
    }
}
