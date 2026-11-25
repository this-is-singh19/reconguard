package io.reconguard.ingestion.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.reconguard.common.multitenancy.TenantContext;
import io.reconguard.ingestion.controller.WebhookController;
import io.reconguard.ingestion.exception.GlobalExceptionHandler;
import io.reconguard.ingestion.producer.TransactionProducer;
import io.reconguard.ingestion.service.NormalizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the real filter + interceptor + controller chain (no @WebMvcTest context shortcuts). */
class HmacVerificationTest {

    private static final String SECRET = "test-secret";
    private static final String BODY = """
            {"transactionId":"tx-1","tenantId":"t1","orderId":"o-1",
             "monetaryAmount":{"value":10.00,"currency":"USD"},
             "type":"PAYMENT","timestamp":"2026-08-02T20:00:00Z"}""";

    private MockMvc mvc;
    private TransactionProducer producer;

    @BeforeEach
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(Boolean.TRUE);
        NormalizationService norm = mock(NormalizationService.class);
        when(norm.normalize(any(io.reconguard.common.model.TransactionEvent.class))).thenAnswer(i -> i.getArgument(0));
        producer = mock(TransactionProducer.class);

        HmacSecurityInterceptor hmac = new HmacSecurityInterceptor();
        ReflectionTestUtils.setField(hmac, "webhookSecret", SECRET);

        TenantContext.setTenantId("t1");
        mvc = MockMvcBuilders.standaloneSetup(new WebhookController(norm, producer, redis, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestCachingFilter())
                .addInterceptors(hmac)
                .build();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req() {
        return post("/api/v1/webhooks/transactions")
                .header("X-Idempotency-Key", "k1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY);
    }

    @Test
    void validSignatureAccepted() throws Exception {
        mvc.perform(req().header("X-Signature", HmacUtils.calculateHmac(BODY, SECRET)))
                .andExpect(status().isAccepted());
        verify(producer).sendTransactionEvent(any());
    }

    @Test
    void tamperedSignatureRejected() throws Exception {
        mvc.perform(req().header("X-Signature", HmacUtils.calculateHmac(BODY + " ", SECRET)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(producer);
    }

    @Test
    void arbitraryNonBlankSignatureRejected() throws Exception {
        mvc.perform(req().header("X-Signature", "anything"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(producer);
    }

    @Test
    void missingSignatureRejected() throws Exception {
        mvc.perform(req()).andExpect(status().isUnauthorized());
        verifyNoInteractions(producer);
    }

    @Test
    void missingSignatureReturnsProblemJson() throws Exception {
        mvc.perform(req())
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Missing Signature"))
                .andExpect(jsonPath("$.type").value("https://api.reconguard.io/errors/missing-signature"));
        verifyNoInteractions(producer);
    }

    @Test
    void tamperedSignatureReturnsProblemJson() throws Exception {
        mvc.perform(req().header("X-Signature", "anything"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.title").value("HMAC verification failed"));
    }

    @Test
    void failsClosedWhenBodyNotCached() throws Exception {
        MockMvc noFilter = MockMvcBuilders.standaloneSetup(new WebhookController(null, producer, null, new ObjectMapper()))
                .addInterceptors(new HmacSecurityInterceptor() {{ ReflectionTestUtils.setField(this, "webhookSecret", SECRET); }})
                .build();
        noFilter.perform(req().header("X-Signature", HmacUtils.calculateHmac(BODY, SECRET)))
                .andExpect(status().is5xxServerError());
    }
}
