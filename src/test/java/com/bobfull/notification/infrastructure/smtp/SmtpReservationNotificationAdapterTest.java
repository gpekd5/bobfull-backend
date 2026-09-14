package com.bobfull.notification.infrastructure.smtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bobfull.reservation.application.port.ReservationNotificationPort.Recipient;
import com.bobfull.reservation.application.port.ReservationNotificationPort.ReservationResultNotification;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

@ExtendWith(MockitoExtension.class)
class SmtpReservationNotificationAdapterTest {
    private static final Instant MEAL_START_AT = Instant.parse("2026-08-10T10:00:00Z");

    @Mock JavaMailSender mailSender;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(SmtpReservationNotificationAdapter.class)).addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        ((Logger) LoggerFactory.getLogger(SmtpReservationNotificationAdapter.class)).detachAppender(logAppender);
    }

    private SmtpReservationNotificationAdapter adapter() {
        return new SmtpReservationNotificationAdapter(mailSender, "no-reply@bobfull.com");
    }

    private void givenMimeMessage() {
        given(mailSender.createMimeMessage()).willReturn(new MimeMessage((Session) null));
    }

    @Test
    void 정상_발송이면_참여자별로_메일을_한_번씩_보낸다() {
        givenMimeMessage();
        ReservationResultNotification notification = notification(
                new Recipient(1L, "a@bobfull.com", "회원A"), new Recipient(2L, "b@bobfull.com", "회원B"));

        adapter().notifyConfirmed(notification);

        verify(mailSender, times(2)).send(any(MimeMessage.class));
    }

    @Test
    void SMTP_발송이_실패하면_한_번만_시도하고_Processor로_예외를_전달한다() {
        givenMimeMessage();
        ReservationResultNotification notification = notification(new Recipient(1L, "a@bobfull.com", "회원A"));
        doThrow(new MailSendException("boom")).when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> adapter().notifyConfirmed(notification)).isInstanceOf(MailSendException.class);

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void 한_참여자의_발송_실패가_다른_참여자_발송을_막지_않는다() {
        givenMimeMessage();
        ReservationResultNotification notification = notification(
                new Recipient(1L, "fail@bobfull.com", "회원A"), new Recipient(2L, "ok@bobfull.com", "회원B"));
        doAnswer(invocation -> {
            MimeMessage message = invocation.getArgument(0);
            if ("fail@bobfull.com".equals(message.getRecipients(Message.RecipientType.TO)[0].toString())) {
                throw new MailSendException("boom");
            }
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> adapter().notifyCancelledDueToInsufficientParticipants(notification))
                .isInstanceOf(MailSendException.class);

        // 실패한 참여자 1회 + 성공한 참여자 1회. 재시도는 Outbox Processor가 담당한다.
        verify(mailSender, times(2)).send(any(MimeMessage.class));
    }

    @Test
    void 메시지_생성_중_예외가_발생해도_다른_참여자_발송을_막지_않는다() {
        given(mailSender.createMimeMessage())
                .willThrow(new IllegalStateException("boom"))
                .willReturn(new MimeMessage((Session) null));
        ReservationResultNotification notification = notification(
                new Recipient(1L, "broken@bobfull.com", "회원A"), new Recipient(2L, "ok@bobfull.com", "회원B"));

        assertThatThrownBy(() -> adapter().notifyConfirmed(notification)).isInstanceOf(IllegalStateException.class);

        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void 예약_접수_알림도_참여자에게_정상_발송된다() {
        givenMimeMessage();
        ReservationResultNotification notification = notification(new Recipient(1L, "created@bobfull.com", "회원A"));

        adapter().notifyReservationCreated(notification);

        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void 참여_완료_알림도_참여자에게_정상_발송된다() {
        givenMimeMessage();
        ReservationResultNotification notification = notification(new Recipient(1L, "joined@bobfull.com", "회원A"));

        adapter().notifyParticipationCompleted(notification);

        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void 예약_접수_안내_문구는_모집_중이라고_단정하지_않는다() throws Exception {
        assertBodyDoesNotAssertRecruiting(adapter()::notifyReservationCreated);
    }

    @Test
    void 참여_완료_안내_문구는_모집_중이라고_단정하지_않는다() throws Exception {
        assertBodyDoesNotAssertRecruiting(adapter()::notifyParticipationCompleted);
    }

    /**
     * 정원 도달로 CREATE·JOIN 결제 완료와 같은 트랜잭션 안에서 모집이 즉시 CLOSED될 수 있으므로
     * (정원 마지막 자리를 채운 JOIN 등), 접수·참여 완료 안내 문구는 실제 모집 상태와 무관하게
     * 항상 참이어야 한다 — "모집 중"이라고 단정하면 이미 CLOSED된 예약에는 거짓 안내가 된다.
     */
    private void assertBodyDoesNotAssertRecruiting(Consumer<ReservationResultNotification> action) throws Exception {
        givenMimeMessage();
        ReservationResultNotification notification = notification(new Recipient(1L, "a@bobfull.com", "회원A"));
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);

        action.accept(notification);

        verify(mailSender).send(captor.capture());
        String plainText = extractPlainText(captor.getValue());
        assertThat(plainText).doesNotContain("모집 중");
    }

    /**
     * {@code MimeMessageHelper(message, true, ...)}는 mixed multipart 안에 text/html 대체본을
     * 담는 alternative multipart를 한 겹 더 감싸므로, 첫 번째 텍스트 파트를 찾을 때까지 재귀적으로
     * 내려가야 한다.
     */
    private String extractPlainText(MimeMessage message) throws Exception {
        Object content = message.getContent();
        while (content instanceof MimeMultipart multipart) {
            content = multipart.getBodyPart(0).getContent();
        }
        return (String) content;
    }

    @Test
    void 발송_실패_로그에_이메일_주소를_남기지_않는다() {
        givenMimeMessage();
        ReservationResultNotification notification = notification(new Recipient(1L, "secret@bobfull.com", "회원A"));
        doThrow(new MailSendException("boom")).when(mailSender).send(any(MimeMessage.class));

        assertThatThrownBy(() -> adapter().notifyConfirmed(notification)).isInstanceOf(MailSendException.class);

        assertThat(logAppender.list).isNotEmpty();
        assertThat(logAppender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains("secret@bobfull.com") || message.contains("@bobfull.com"));
    }

    private ReservationResultNotification notification(Recipient... recipients) {
        return new ReservationResultNotification(1L, "밥풀식당", "제주시", MEAL_START_AT, List.of(recipients));
    }
}
