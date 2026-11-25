package io.reconguard.reconciliation.repository;

import io.reconguard.reconciliation.domain.TransactionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<TransactionRecord, String> {

    @Query("SELECT t FROM TransactionRecord t WHERE t.orderId = :orderId AND t.tenantId = :tenantId AND t.status = 'UNRESOLVED'")
    Optional<TransactionRecord> findUnresolvedByOrderIdAndTenant(
            @Param("orderId") String orderId,
            @Param("tenantId") String tenantId
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
UPDATE TransactionRecord t
SET
    t.status = 'MATCHED',
    t.gatewayTransactionId = :gatewayTransactionId,
    t.reconciledAt = :reconciledAt
WHERE
    t.id = :targetId
    AND t.tenantId = :tenantId
""")
    void linkAndMatch(
            @Param("targetId") String targetId,
            @Param("gatewayTransactionId") String gatewayTransactionId,
            @Param("tenantId") String tenantId,
            @Param("reconciledAt") Instant reconciledAt
    );

    @Query("SELECT COUNT(t) FROM TransactionRecord t WHERE t.status = :status AND t.createdAt < :cutoff")
    long countByStatusAndCreatedAtBefore(
            @Param("status") String status,
            @Param("cutoff") Instant cutoff
    );

    @Query("SELECT t FROM TransactionRecord t WHERE t.status = 'UNRESOLVED' AND t.createdAt < :cutoff")
    List<TransactionRecord> findStaleUnresolved(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("UPDATE TransactionRecord t SET t.status = 'FAILED' WHERE t.id IN :ids")
    void markAsFailed(@Param("ids") List<String> ids);
}