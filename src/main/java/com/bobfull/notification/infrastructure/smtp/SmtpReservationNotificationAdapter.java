package com.bobfull.notification.infrastructure.smtp;

import com.bobfull.reservation.application.port.ReservationNotificationPort;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

// 예약·참여 결과 알림을 HTML 이메일로 구성해 SMTP 서버에 전송한다.
@Slf4j
@Component
public class SmtpReservationNotificationAdapter implements ReservationNotificationPort {

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
        // 결제 직후 모집이 마감될 수 있으므로 현재 모집 상태를 단정하지 않는 문구를 사용한다.
        send(notification, "CREATED", "[밥풀] 예약 접수가 완료되었습니다",
                "예약 접수가 완료됐어요", "#1c7ed6",
                "최종 예약 상태는 밥풀에서 확인할 수 있으며, 모집 마감 처리 대상인 경우 결과를 별도로 안내드립니다.");
    }

    @Override
    public void notifyParticipationCompleted(ReservationResultNotification notification) {
        // 결제 직후 모집이 마감될 수 있으므로 현재 모집 상태를 단정하지 않는 문구를 사용한다.
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

        // 한 수신자의 실패가 나머지 발송을 막지 않게 모두 시도한 뒤 실패를 Processor에 전달한다.
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
        // 개인정보가 로그에 남지 않도록 이메일 주소와 본문 대신 내부 식별자만 기록한다.
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
