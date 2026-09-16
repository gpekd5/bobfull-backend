package com.bobfull.notification.infrastructure.outbox;

import com.bobfull.notification.infrastructure.repository.EmailOutboxDeliveryRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 수신자별 이메일 성공 상태를 독립 트랜잭션으로 확정한다.
@Service
@RequiredArgsConstructor
public class EmailOutboxDeliveryTransactionService {

    private final EmailOutboxDeliveryRepository repository;

    // 성공한 수신자는 이후 Outbox 재시도에서 제외되도록 즉시 SENT로 전이한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSent(Long id, Instant now) {
        return repository.markSent(id, EmailDeliveryStatus.PENDING, EmailDeliveryStatus.SENT, now) == 1;
    }
}
