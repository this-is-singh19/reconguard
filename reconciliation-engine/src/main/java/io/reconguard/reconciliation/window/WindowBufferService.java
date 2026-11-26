package io.reconguard.reconciliation.window;

import io.reconguard.common.model.TransactionEvent;
import io.reconguard.common.model.TransactionEventType;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;

/**
 * Short-lived buffer of unmatched legs (A3.5 cardinality rule: at most one buffered leg per order per side).
 * Each side has its own slot, so an ORDER waiting for a PAYMENT is not overwritten by a non-matching PAYMENT.
 * Same-side duplicates still overwrite their own slot; the DB keeps every leg, and the sweeper fails the rest.
 */
@Service
public class WindowBufferService {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String BUFFER_PREFIX = "reconguard:window:buffer:";
    private static final long WINDOW_TTL_SECONDS = 35;

    public WindowBufferService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Stashes an unmatched transaction leg into the temporary 35-second window buffer.
     */
    public void bufferEvent(TransactionEvent event) {
        redisTemplate.opsForValue().set(key(event.tenantId(), event.orderId(), event.type()),
                event, Duration.ofSeconds(WINDOW_TTL_SECONDS));
    }

    /**
     * Returns the buffered leg of the given side (the counterparty of the incoming event), or null.
     */
    public TransactionEvent getBufferedCounterparty(String tenantId, String orderId, TransactionEventType counterpartyType) {
        Object cached = redisTemplate.opsForValue().get(key(tenantId, orderId, counterpartyType));
        return cached instanceof TransactionEvent event ? event : null;
    }

    /**
     * Removes the buffered leg of the given side once a match has been finalized.
     */
    public void removeEvent(String tenantId, String orderId, TransactionEventType side) {
        redisTemplate.delete(key(tenantId, orderId, side));
    }

    private static String key(String tenantId, String orderId, TransactionEventType side) {
        return BUFFER_PREFIX + tenantId + ":" + orderId + ":" + side.name();
    }
}
