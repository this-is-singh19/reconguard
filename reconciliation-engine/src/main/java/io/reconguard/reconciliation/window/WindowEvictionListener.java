package io.reconguard.reconciliation.window;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.KeyExpirationEventMessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WindowEvictionListener extends KeyExpirationEventMessageListener {

    public WindowEvictionListener(RedisMessageListenerContainer listenerContainer) {
        super(listenerContainer);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = message.toString();
        if (expiredKey.startsWith("reconguard:window:buffer:")) {
            log.info("Window TTL expired for buffer key: {}. Leg remains UNRESOLVED in DB.", expiredKey);
        }
    }
}