package com.bobfull.payment.application.service;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bobfull.common.monitoring.BusinessMetricRecorder;
import com.bobfull.payment.domain.entity.*;
import com.bobfull.payment.infrastructure.repository.PaymentRepository;
import java.math.BigDecimal; import java.time.*; import java.util.Optional;
import org.junit.jupiter.api.*; import org.junit.jupiter.api.extension.ExtendWith; import org.mockito.*; import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
@ExtendWith(MockitoExtension.class) class PaymentExpirationProcessorTest {
 @Mock PaymentRepository repository;
 @Mock BusinessMetricRecorder businessMetricRecorder;
 Payment payment(Instant expires){return Payment.createReady("p",1L,2L,null,PaymentPurpose.CREATE,1,BigDecimal.TEN,expires);}
 @Test void 내부PK_잠금으로_만료READY만_전이한다(){ Instant now=Instant.parse("2026-07-30T00:00:00Z"); Payment p=payment(now); when(repository.findWithLockById(7L)).thenReturn(Optional.of(p)); new PaymentExpirationProcessor(repository,Clock.fixed(now,ZoneOffset.UTC),businessMetricRecorder).expire(7L); verify(repository).findWithLockById(7L); Assertions.assertEquals(PaymentStatus.EXPIRED,p.getStatus()); }
 @Test void 미래_READY는_유지되고_반복은_멱등이다(){ Instant now=Instant.parse("2026-07-30T00:00:00Z"); Payment p=payment(now.plusSeconds(1)); when(repository.findWithLockById(7L)).thenReturn(Optional.of(p)); PaymentExpirationProcessor x=new PaymentExpirationProcessor(repository,Clock.fixed(now,ZoneOffset.UTC),businessMetricRecorder); x.expire(7L); x.expire(7L); Assertions.assertEquals(PaymentStatus.READY,p.getStatus()); verify(repository,times(2)).findWithLockById(7L); }
 @Test void READY_만료_전이시_구조화로그를_남긴다(){ Instant now=Instant.parse("2026-07-30T00:00:00Z"); Payment p=payment(now); ReflectionTestUtils.setField(p,"id",7L); when(repository.findWithLockById(7L)).thenReturn(Optional.of(p)); Logger logger=(Logger)LoggerFactory.getLogger(PaymentExpirationProcessor.class); ListAppender<ILoggingEvent> appender=new ListAppender<>(); appender.start(); logger.addAppender(appender); try{new PaymentExpirationProcessor(repository,Clock.fixed(now,ZoneOffset.UTC),businessMetricRecorder).expire(7L);} finally{logger.detachAppender(appender);} assertThat(appender.list).singleElement().satisfies(event -> { assertThat(event.getFormattedMessage()).contains("event=READY_PAYMENT_EXPIRED"); assertThat(event.getFormattedMessage()).contains("paymentInternalId=7"); assertThat(event.getFormattedMessage()).contains("afterStatus=EXPIRED"); }); }
}
