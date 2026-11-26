package io.reconguard.reconciliation.repository;

import io.reconguard.reconciliation.domain.DeadLetterEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, Long> {
}
