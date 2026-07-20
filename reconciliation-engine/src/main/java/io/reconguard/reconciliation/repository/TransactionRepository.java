package io.reconguard.reconciliation.repository;

import io.reconguard.reconciliation.model.TransactionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<TransactionRecord, String> {

    @Query("SELECT t FROM TransactionRecord t WHERE t.orderId = :orderId AND t.tenantId = :tenantId AND t.status = 'UNRESOLVED'")
    Optional<TransactionRecord> findUnresolvedByOrderIdAndTenant(
            @Param("orderId") String orderId,
            @Param("tenantId") String tenantId
    );

    @Modifying
    @Query("UPDATE TransactionRecord t SET t.status = 'MATCHED', t.matchedWithTransactionId = :counterpartyId, t.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE t.transactionId = :targetId AND t.tenantId = :tenantId")
    void linkAndMatch(
            @Param("targetId") String targetId,
            @Param("counterpartyId") String counterpartyId,
            @Param("tenantId") String tenantId
    );
}