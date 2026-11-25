package io.reconguard.ai.repository;

import io.reconguard.ai.domain.ResolutionSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResolutionSuggestionRepository extends JpaRepository<ResolutionSuggestion, String> {
    List<ResolutionSuggestion> findByTenantIdAndTransactionId(String tenantId, String transactionId);
}