package com.bobfull.notification.infrastructure.smtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.bobfull.reservation.application.port.ReservationNotificationPort.Recipient;
import com.bobfull.reservation.application.port.ReservationNotificationPort.ReservationResultNotification;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * 실제 Gmail SMTP로 확정·취소·접수·참여 완료 안내 테스트 메일을 보내 로컬 메일 설정을 수동으로
 * 검증한다(Issue #168 직접 검증). 평소에는 항상 비활성화돼 있고 CI·전체 테스트에서 절대 실행되지
 * 않는다.
 *
 * Spring Context를 올리지 않고 프로젝트 루트의 {@code .env} 파일을 직접 읽어 SMTP 설정을 구성한다
 * — DB·Redis 같은 다른 인프라 없이 메일 발송 자체만 확인하기 위해서다.
 *
 * 실행 방법:
 * 1. 프로젝트 루트 {@code .env}에 실제 Gmail SMTP 정보(MAIL_HOST, MAIL_PORT, MAIL_USERNAME,
 *    MAIL_PASSWORD, MAIL_SMTP_AUTH, MAIL_SMTP_STARTTLS, NOTIFICATION_EMAIL_FROM_ADDRESS)를 채운다.
 * 2. 아래 {@code @Disabled} 줄을 잠시 지운 뒤, 확인하고 싶은 메서드 하나만(또는 클래스 전체를) 실행한다.
 *    예: ./gradlew :test --tests "com.bobfull.notification.infrastructure.smtp.ManualSmtpSendVerification"
 * 3. 본인 Gmail 수신함에서 실제 도착을 직접 확인한 뒤, {@code @Disabled}를 다시 복원한다.
 */
@Disabled("실제 Gmail 발송을 수동으로 확인할 때만 이 줄을 지우고 단독 실행한다")
class ManualSmtpSendVerification {

    @Test
    void 본인_Gmail로_확정_안내_테스트_메일을_보낸다() {
        String username = send(SmtpReservationNotificationAdapter::notifyConfirmed);

        // 실제 도착 여부는 자동 검증할 수 없다 — 본인 Gmail 수신함에서 직접 확인한다.
        assertThat(username).isNotBlank();
    }

    @Test
    void 본인_Gmail로_취소_안내_테스트_메일을_보낸다() {
        String username = send(SmtpReservationNotificationAdapter::notifyCancelledDueToInsufficientParticipants);

        // 실제 도착 여부는 자동 검증할 수 없다 — 본인 Gmail 수신함에서 직접 확인한다.
        assertThat(username).isNotBlank();
    }

    @Test
    void 본인_Gmail로_예약_접수_테스트_메일을_보낸다() {
        String username = send(SmtpReservationNotificationAdapter::notifyReservationCreated);

        // 실제 도착 여부는 자동 검증할 수 없다 — 본인 Gmail 수신함에서 직접 확인한다.
        assertThat(username).isNotBlank();
    }

    @Test
    void 본인_Gmail로_참여_완료_테스트_메일을_보낸다() {
        String username = send(SmtpReservationNotificationAdapter::notifyParticipationCompleted);

        // 실제 도착 여부는 자동 검증할 수 없다 — 본인 Gmail 수신함에서 직접 확인한다.
        assertThat(username).isNotBlank();
    }

    private String send(java.util.function.BiConsumer<SmtpReservationNotificationAdapter, ReservationResultNotification> action) {
        Map<String, String> env = readDotEnv();

        String username = requireEnv(env, "MAIL_USERNAME");
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(requireEnv(env, "MAIL_HOST"));
        mailSender.setPort(Integer.parseInt(requireEnv(env, "MAIL_PORT")));
        mailSender.setUsername(username);
        mailSender.setPassword(requireEnv(env, "MAIL_PASSWORD"));

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.smtp.auth", env.getOrDefault("MAIL_SMTP_AUTH", "true"));
        props.put("mail.smtp.starttls.enable", env.getOrDefault("MAIL_SMTP_STARTTLS", "true"));

        String fromAddress = env.getOrDefault("NOTIFICATION_EMAIL_FROM_ADDRESS", username);
        SmtpReservationNotificationAdapter adapter = new SmtpReservationNotificationAdapter(mailSender, fromAddress);
        ReservationResultNotification notification = new ReservationResultNotification(
                0L, "수동 검증 식당", "제주시 수동 검증로 1", Instant.now(), List.of(new Recipient(0L, username, "수동 검증")));

        action.accept(adapter, notification);
        return username;
    }

    private Map<String, String> readDotEnv() {
        Path envFile = Path.of(".env");
        Map<String, String> values = new HashMap<>();
        if (!Files.exists(envFile)) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(envFile)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                int separatorIndex = trimmed.indexOf('=');
                String key = trimmed.substring(0, separatorIndex).trim();
                String value = trimmed.substring(separatorIndex + 1).trim();
                values.put(key, value);
            }
        } catch (IOException exception) {
            fail(".env 파일을 읽는 중 오류가 발생했습니다: " + exception.getMessage());
        }
        return values;
    }

    private String requireEnv(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) {
            fail("프로젝트 루트 .env 파일에 " + key + " 값을 채워주세요.");
        }
        return value;
    }
}
