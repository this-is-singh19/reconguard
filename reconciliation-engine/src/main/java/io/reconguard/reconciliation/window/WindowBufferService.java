package io.reconguard.reconciliation.window;

import io.reconguard.common.model.TransactionEvent;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;

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
        String key = BUFFER_PREFIX + event.tenantId() + ":" + event.orderId();

        // Cache the transaction event object, expiring after our 35-second window SLA
        redisTemplate.opsForValue().set(key, event, Duration.ofSeconds(WINDOW_TTL_SECONDS));
    }

    /**
     * Tries to fetch a buffered counter-leg for a matching order ID.
     */
    public TransactionEvent getBufferedCounterparty(String tenantId, String orderId) {
        String key = BUFFER_PREFIX + tenantId + ":" + orderId;
        Object cached = redisTemplate.opsForValue().get(key);

        if (cached instanceof TransactionEvent) {
            return (TransactionEvent) cached;
        }
        return null;
    }

    /**
     * Removes the item from the buffer once a successful match is finalized.
     */
    public void removeEvent(String tenantId, String orderId) {
        String key = BUFFER_PREFIX + tenantId + ":" + orderId;
        redisTemplate.delete(key);
    }
}