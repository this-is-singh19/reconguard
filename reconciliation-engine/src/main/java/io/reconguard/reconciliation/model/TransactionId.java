package io.reconguard.reconciliation.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class TransactionId implements Serializable {

    @Column(name = "transaction_id", length = 64, nullable = false)
    private String transactionId;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    public TransactionId() {}

    public TransactionId(String transactionId, String tenantId) {
        this.transactionId = transactionId;
        this.tenantId = tenantId;
    }

    // Getters, Setters, hashCode, and equals
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransactionId that = (TransactionId) o;
        return Objects.equals(transactionId, that.transactionId) &&
               Objects.equals(tenantId, that.tenantId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transactionId, tenantId);
    }
}