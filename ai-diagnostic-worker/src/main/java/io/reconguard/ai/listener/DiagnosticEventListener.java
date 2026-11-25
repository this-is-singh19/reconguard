package io.reconguard.ai.listener;

import io.reconguard.ai.service.DiagnosticWorkerService;
import io.reconguard.common.event.TransactionFailureEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiagnosticEventListener {

    private final DiagnosticWorkerService diagnosticWorkerService;

    @KafkaListener(
            topics = "${reconguard.topics.failed-transactions:reconguard.transactions.failures}",
            groupId = "${spring.kafka.consumer.group-id:reconguard-ai-diagnostic-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handleTransactionFailure(TransactionFailureEvent event) {
        log.info("Received transaction failure event for TxId: {}, OrderId: {}",
                event.transactionId(), event.orderId());

        try {
            diagnosticWorkerService.diagnoseFailure(event, 3);
        } catch (Exception e) {
            log.error("Failed to execute AI diagnostics for TxId: {}", event.transactionId(), e);
            throw e; // Triggers Kafka retry & DLQ error handler
        }
    }
}