package com.bobfull.notification.infrastructure.outbox;

import com.bobfull.notification.infrastructure.repository.EmailOutboxDeliveryRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EmailOutboxDeliveryTransactionService {

    private final EmailOutboxDeliveryRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markSent(Long id, Instant now) {
        return repository.markSent(id, EmailDeliveryStatus.PENDING, EmailDeliveryStatus.SENT, now) == 1;
    }
}
