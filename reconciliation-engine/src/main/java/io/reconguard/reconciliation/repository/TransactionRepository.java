package io.reconguard.reconciliation.repository;

import io.reconguard.reconciliation.domain.TransactionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<TransactionRecord, String> {

    /**
     * All UNRESOLVED legs of an order for a tenant. Returns a list because one order can have several
     * unresolved legs (A3.5); the caller picks the counterpart that matches within tolerance.
     */
    @Query("SELECT t FROM TransactionRecord t WHERE t.orderId = :orderId AND t.tenantId = :tenantId AND t.status = 'UNRESOLVED'")
    List<TransactionRecord> findUnresolvedByOrderIdAndTenant(
            @Param("orderId") String orderId,
            @Param("tenantId") String tenantId
    );

    /** Tenant-scoped existence check: a transaction id is only "seen" for the tenant that sent it (A3.12). */
    boolean existsByIdAndTenantId(String id, String tenantId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
UPDATE TransactionRecord t
SET
    t.status = 'MATCHED',
    t.matchedWithTransactionId = :matchedWithTransactionId,
    t.updatedAt = :updatedAt
WHERE
    t.id = :targetId
    AND t.tenantId = :tenantId
    AND t.status = 'UNRESOLVED'
""")
    int linkAndMatch(
            @Param("targetId") String targetId,
            @Param("matchedWithTransactionId") String matchedWithTransactionId,
            @Param("tenantId") String tenantId,
            @Param("updatedAt") Instant updatedAt
    );

    @Query("SELECT t FROM TransactionRecord t WHERE t.status = 'UNRESOLVED' AND t.createdAt < :cutoff")
    List<TransactionRecord> findStaleUnresolved(@Param("cutoff") Instant cutoff);

    /**
     * Status-guarded transition (A3.7): only rows still UNRESOLVED move to FAILED, so a leg matched
     * concurrently is not failed. Returns the number of rows actually transitioned.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TransactionRecord t SET t.status = 'FAILED' WHERE t.id IN :ids AND t.status = 'UNRESOLVED'")
    int markAsFailed(@Param("ids") List<String> ids);
}
