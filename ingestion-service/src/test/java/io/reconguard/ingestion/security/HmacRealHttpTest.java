package io.reconguard.ingestion.security;

import io.reconguard.ingestion.producer.TransactionProducer;
import io.reconguard.ingestion.service.NormalizationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 0F.2: HMAC over a real HTTP request through the full servlet stack (embedded server, real filter chain). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "reconguard.webhook.secrets.t1=real-http-secret")
class HmacRealHttpTest {

    private static final String SECRET = "real-http-secret";
    private static final String BODY = """
            {"transactionId":"tx-1","tenantId":"t1","orderId":"o-1",
             "monetaryAmount":{"value":10.00,"currency":"USD"},
             "type":"PAYMENT","timestamp":"2026-08-02T20:00:00Z"}""";

    @LocalServerPort int port;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean TransactionProducer producer;
    @MockitoBean NormalizationService normalization;

    private HttpResponse<String> post(String signature) throws Exception {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.TRUE);
        when(normalization.normalize(any(io.reconguard.common.model.TransactionEvent.class)))
                .thenAnswer(i -> i.getArgument(0));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/webhooks/transactions"))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", "t1")
                .header("X-Idempotency-Key", "k-" + System.nanoTime())
                .POST(HttpRequest.BodyPublishers.ofString(BODY));
        if (signature != null) {
            b.header("X-Signature", signature);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void validSignatureAccepted() throws Exception {
        assertThat(post(HmacUtils.calculateHmac(BODY, SECRET)).statusCode()).isEqualTo(202);
        verify(producer).sendTransactionEvent(any());
    }

    @Test
    void tamperedSignatureRejected() throws Exception {
        assertThat(post(HmacUtils.calculateHmac(BODY + " ", SECRET)).statusCode()).isEqualTo(403);
        verifyNoInteractions(producer);
    }

    @Test
    void missingSignatureRejected() throws Exception {
        assertThat(post(null).statusCode()).isEqualTo(401);
        verifyNoInteractions(producer);
    }
}
