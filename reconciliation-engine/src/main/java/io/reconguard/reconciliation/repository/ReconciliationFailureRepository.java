package io.reconguard.reconciliation.repository;

import io.reconguard.reconciliation.domain.ReconciliationFailure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ReconciliationFailureRepository extends JpaRepository<ReconciliationFailure, UUID> {
    boolean existsByTenantIdAndTransactionId(String tenantId, String transactionId);
}
