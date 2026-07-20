package io.reconguard.reconciliation.idempotency;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.Duration;

@Component
public class IdempotencyGuard {

    private final StringRedisTemplate redisTemplate;
    private static final String KEY_PREFIX = "reconguard:cache:idempotency:";
    private static final long DEFAULT_TTL_HOURS = 24;

    public IdempotencyGuard(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Atomically claims a transaction ID within a specific tenant namespace.
     */
    public boolean checkAndClaim(String tenantId, String transactionId) {
        // Enforcing strict tenant isolation boundaries in our cache keys
        String lockKey = KEY_PREFIX + tenantId + ":" + transactionId;

        Boolean isUnique = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "CLAIMED", Duration.ofHours(DEFAULT_TTL_HOURS));

        return isUnique != null && isUnique;
    }

    /**
     * Evicts the idempotency mark if downstream engine execution fails.
     */
    public void evictClaim(String tenantId, String transactionId) {
        String lockKey = KEY_PREFIX + tenantId + ":" + transactionId;
        redisTemplate.delete(lockKey);
    }
}