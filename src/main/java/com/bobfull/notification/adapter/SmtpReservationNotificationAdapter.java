package com.bobfull.notification.adapter;

import com.bobfull.reservation.application.port.ReservationNotificationPort;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 실제 SMTP 서버로 예약 결과(확정·인원 미달 취소) 및 결제 완료(접수·참여) 안내 메일을 발송한다
 * (Issue #183). SMTP 호출은 수신자마다 정확히 한 번만 시도하고 실패를 Processor에 전달한다.
 * 재시도와 최종 FAILED 전이는 공통 Outbox가 단독으로 책임진다. 이메일 주소·본문은 로그에 남기지
 * 않는다. 이미지 첨부 없이 CSS만으로 꾸민 HTML 본문을 사용한다. 접수·참여 완료
 * 안내는 결제 완료 시점에 이미 정원이 차 모집이 즉시 마감(CLOSED)될 수도 있으므로, "확정"이라
 * 표현하지 않는 것은 물론 "모집 중"이라고 단정하지도 않는다 — 실제 모집 상태와 무관하게 참인
 * 상태 중립 문구만 사용한다.
 */
@Component
public class SmtpReservationNotificationAdapter implements ReservationNotificationPort {
    private static final Logger log = LoggerFactory.getLogger(SmtpReservationNotificationAdapter.class);
    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter MEAL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy. MM. dd (E)", Locale.KOREAN).withZone(SEOUL_ZONE);
    private static final DateTimeFormatter MEAL_TIME_FORMAT =
            DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN).withZone(SEOUL_ZONE);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpReservationNotificationAdapter(
            JavaMailSender mailSender,
            @Value("${notification.email.from-address:no-reply@bobfull.com}") String fromAddress
    ) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void notifyConfirmed(ReservationResultNotification notification) {
        send(notification, "CONFIRMED", "[밥풀] 예약이 확정되었습니다",
                "예약이 확정됐어요 🎉", "#2f9e44", "즐거운 식사 되세요!");
    }

    @Override
    public void notifyCancelledDueToInsufficientParticipants(ReservationResultNotification notification) {
        send(notification, "CANCELLED", "[밥풀] 예약이 취소되었습니다",
                "예약이 취소됐어요", "#e03131", "최소 인원 미달로 취소되었습니다. 결제 금액은 환불 절차가 진행됩니다.");
    }

    @Override
    public void notifyReservationCreated(ReservationResultNotification notification) {
        send(notification, "CREATED", "[밥풀] 예약 접수가 완료되었습니다",
                "예약 접수가 완료됐어요", "#1c7ed6",
                "최종 예약 상태는 밥풀에서 확인할 수 있으며, 모집 마감 처리 대상인 경우 결과를 별도로 안내드립니다.");
    }

    @Override
    public void notifyParticipationCompleted(ReservationResultNotification notification) {
        send(notification, "JOINED", "[밥풀] 합석 참여가 완료되었습니다",
                "참여가 완료됐어요", "#1c7ed6",
                "최종 예약 상태는 밥풀에서 확인할 수 있으며, 모집 마감 처리 대상인 경우 결과를 별도로 안내드립니다.");
    }

    private void send(
            ReservationResultNotification notification, String result, String subject,
            String title, String accentColor, String message
    ) {
        String mealDate = MEAL_DATE_FORMAT.format(notification.mealStartAt());
        String mealTime = MEAL_TIME_FORMAT.format(notification.mealStartAt());
        String restaurantName = escapeHtml(notification.restaurantName());
        String restaurantAddress = escapeHtml(notification.restaurantAddress());
        String htmlBody = buildHtmlBody(title, accentColor, restaurantName, mealDate, mealTime, restaurantAddress, message);
        String textBody = "%s\n식당: %s\n주소: %s\n예약 날짜: %s\n식사 시작 시간: %s\n%s".formatted(
                title, notification.restaurantName(), notification.restaurantAddress(), mealDate, mealTime, message);

        RuntimeException failure = null;
        for (Recipient recipient : notification.recipients()) {
            try {
                sendToRecipient(notification.reservationId(), recipient, result, subject, htmlBody, textBody);
            } catch (RuntimeException exception) {
                failure = exception;
            }
        }
        if (failure != null) throw failure;
    }

    private void sendToRecipient(
            Long reservationId, Recipient recipient, String result, String subject, String htmlBody, String textBody
    ) {
        MimeMessage message;
        try {
            message = buildMessage(recipient.email(), subject, htmlBody, textBody);
        } catch (RuntimeException exception) {
            // buildMessage 자체가 (MessagingException 외의) 예상치 못한 예외를 던져도 이 참여자
            // 건만 실패로 남기고, 나머지 참여자 발송은 계속 진행한다.
            message = null;
        }
        if (message == null) {
            log.error("event=RESERVATION_NOTIFICATION_FAILED reservationId={} memberId={} result={} reason=MESSAGE_BUILD_FAILED",
                    reservationId, recipient.memberId(), result);
            throw new IllegalStateException("MESSAGE_BUILD_FAILED");
        }

        try {
            mailSender.send(message);
            log.info("event=RESERVATION_NOTIFICATION_SENT reservationId={} memberId={} result={}",
                    reservationId, recipient.memberId(), result);
        } catch (MailException exception) {
            log.error("event=RESERVATION_NOTIFICATION_FAILED reservationId={} memberId={} result={}",
                    reservationId, recipient.memberId(), result);
            throw exception;
        }
    }

    private MimeMessage buildMessage(String to, String subject, String htmlBody, String textBody) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(textBody, htmlBody);
            return message;
        } catch (MessagingException exception) {
            return null;
        }
    }

    private String buildHtmlBody(
            String title, String accentColor, String restaurantName, String mealDate,
            String mealTime, String restaurantAddress, String message
    ) {
        return """
                <div style="font-family:-apple-system,'Apple SD Gothic Neo','Malgun Gothic',sans-serif;max-width:420px;margin:0 auto;padding:32px 24px;border:1px solid #eee;border-radius:16px;">
                  <p style="color:#999;font-size:13px;margin:0 0 4px;">밥풀</p>
                  <h2 style="color:%s;margin:0 0 8px;font-size:22px;">%s</h2>
                  <p style="color:#555;font-size:14px;line-height:1.6;margin:0 0 20px;">%s</p>
                  <table style="width:100%%;border-collapse:collapse;background:#fafafa;border-radius:12px;">
                    <tr>
                      <td style="padding:12px 16px;color:#888;font-size:14px;">식당명</td>
                      <td style="padding:12px 16px;text-align:right;font-weight:600;font-size:14px;">%s</td>
                    </tr>
                    <tr>
                      <td style="padding:12px 16px;color:#888;font-size:14px;">예약 날짜</td>
                      <td style="padding:12px 16px;text-align:right;font-weight:600;font-size:14px;">%s</td>
                    </tr>
                    <tr>
                      <td style="padding:12px 16px;color:#888;font-size:14px;">식사 시작 시간</td>
                      <td style="padding:12px 16px;text-align:right;font-weight:600;font-size:14px;">%s</td>
                    </tr>
                    <tr>
                      <td style="padding:12px 16px;color:#888;font-size:14px;">주소</td>
                      <td style="padding:12px 16px;text-align:right;font-weight:600;font-size:14px;">%s</td>
                    </tr>
                  </table>
                  <p style="color:#bbb;font-size:12px;text-align:center;margin:24px 0 0;">밥풀 · 혼밥이 모여, 한 테이블이 되는 곳</p>
                </div>
                """.formatted(accentColor, title, message, restaurantName, mealDate, mealTime, restaurantAddress);
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
