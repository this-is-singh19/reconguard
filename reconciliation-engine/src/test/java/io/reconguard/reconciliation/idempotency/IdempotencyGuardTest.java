package io.reconguard.reconciliation.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyGuardTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private IdempotencyGuard guard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        guard = new IdempotencyGuard(redis);
    }

    @Test
    void firstClaimWinsWithTenantScopedKeyAnd24hTtl() {
        String key = "reconguard:cache:idempotency:t1:tx-1";
        when(ops.setIfAbsent(key, "CLAIMED", Duration.ofHours(24))).thenReturn(true);

        assertThat(guard.checkAndClaim("t1", "tx-1")).isTrue();
        verify(ops).setIfAbsent(key, "CLAIMED", Duration.ofHours(24));
    }

    @Test
    void duplicateClaimRejected() {
        when(ops.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);
        assertThat(guard.checkAndClaim("t1", "tx-1")).isFalse();
    }

    @Test
    void nullRedisResultTreatedAsNotClaimed() {
        when(ops.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(null);
        assertThat(guard.checkAndClaim("t1", "tx-1")).isFalse();
    }

    @Test
    void sameTransactionIdInDifferentTenantsUsesDifferentKeys() {
        when(ops.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
        guard.checkAndClaim("t1", "tx-1");
        guard.checkAndClaim("t2", "tx-1");
        verify(ops).setIfAbsent("reconguard:cache:idempotency:t1:tx-1", "CLAIMED", Duration.ofHours(24));
        verify(ops).setIfAbsent("reconguard:cache:idempotency:t2:tx-1", "CLAIMED", Duration.ofHours(24));
    }

    @Test
    void evictDeletesTenantScopedKey() {
        guard.evictClaim("t1", "tx-1");
        verify(redis).delete("reconguard:cache:idempotency:t1:tx-1");
    }
}
