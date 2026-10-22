package com.bobfull.admin.infrastructure.repository.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.bobfull.admin.application.model.MemberModerationReviewStatus;
import com.bobfull.admin.application.service.AdminModerationReportService;
import com.bobfull.admin.presentation.request.AdminReportReviewRequest;
import com.bobfull.chat.application.service.ChatRoomMemberReportService;
import com.bobfull.chat.domain.entity.ChatMessage;
import com.bobfull.chat.domain.entity.ChatModeration;
import com.bobfull.chat.domain.entity.ChatRoom;
import com.bobfull.chat.domain.entity.ModerationCategory;
import com.bobfull.chat.domain.entity.ModerationProcessingStatus;
import com.bobfull.chat.domain.entity.ModerationResultType;
import com.bobfull.chat.domain.entity.ReportReason;
import com.bobfull.chat.domain.entity.ReportStatus;
import com.bobfull.chat.domain.entity.ReviewDecision;
import com.bobfull.chat.domain.entity.RiskLevel;
import com.bobfull.chat.infrastructure.ai.ModerationPrompt;
import com.bobfull.chat.infrastructure.repository.ChatMessageRepository;
import com.bobfull.chat.infrastructure.repository.ChatModerationRepository;
import com.bobfull.chat.infrastructure.repository.ChatRoomMemberReportRepository;
import com.bobfull.chat.infrastructure.repository.ChatRoomRepository;
import com.bobfull.chat.presentation.request.ChatRoomMemberReportCreateRequest;
import com.bobfull.common.config.JpaAuditingConfig;
import com.bobfull.reservation.domain.entity.ReservationParticipant;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import com.bobfull.reservation.infrastructure.repository.query.MyReservationQueryRepositoryImpl;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Provider/Kafka는 호출하지 않는다. 격리된 MySQL의 실제 commit과 Production 관리자/신고 경계만 검증한다.
@Testcontainers
class Issue68ReviewSignalIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");

    @Container
    static final GenericContainer<?> MYSQL = new GenericContainer<>("mysql:8.4")
            .withEnv("MYSQL_ROOT_PASSWORD", "issue68-fixture-only")
            .withEnv("MYSQL_DATABASE", "issue68_review")
            .withExposedPorts(3306)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

    @Test
    void HIGH와_MEDIUM은_각각_2더하기1_경계에서_동일하게_검토대상_3건이다() {
        run(context -> {
            var query = context.getBean(MemberModerationQueryRepository.class);
            for (long member : List.of(101L, 102L)) {
                save(context, member, ModerationResultType.FLAGGED, RiskLevel.MEDIUM);
                save(context, member, ModerationResultType.FLAGGED, RiskLevel.MEDIUM);
                assertThat(query.findMemberSummary(member).orElseThrow().reviewTargetCount()).isEqualTo(2);
            }
            save(context, 101L, ModerationResultType.FLAGGED, RiskLevel.HIGH);
            save(context, 102L, ModerationResultType.FLAGGED, RiskLevel.MEDIUM);

            var required = query.findMemberSummaries(MemberModerationReviewStatus.REVIEW_REQUIRED, PageRequest.of(0, 20));
            assertThat(required.getContent()).extracting(s -> s.memberId()).containsExactlyInAnyOrder(101L, 102L);
            assertThat(required.getContent()).allMatch(s -> s.reviewTargetCount() == 3);
            assertThat(query.findFlaggedEvidences(102L)).hasSize(3).allMatch(e -> e.countedForReview());
            assertThat(query.findRiskCounts(101L)).containsEntry(RiskLevel.HIGH, 1L);
            assertThat(query.findRiskCounts(102L)).containsEntry(RiskLevel.MEDIUM, 3L);
        });
    }

    @Test
    void SAFE또는_LOW_오판은_완료_DB가_3건이어도_검토2건으로_경계신호를_잃는다() {
        run(context -> {
            var query = context.getBean(MemberModerationQueryRepository.class);
            for (long member : List.of(201L, 202L)) {
                save(context, member, ModerationResultType.FLAGGED, RiskLevel.MEDIUM);
                save(context, member, ModerationResultType.FLAGGED, RiskLevel.HIGH);
            }
            save(context, 201L, ModerationResultType.SAFE, RiskLevel.LOW);
            // Validator가 허용하는 FLAGGED/LOW 저장과 Prompt 정책 위반은 서로 다른 계약이다.
            save(context, 202L, ModerationResultType.FLAGGED, RiskLevel.LOW);

            assertThat(context.getBean(ChatModerationRepository.class).count()).isEqualTo(6);
            assertThat(query.findMemberSummary(201L).orElseThrow().reviewTargetCount()).isEqualTo(2);
            assertThat(query.findMemberSummary(202L).orElseThrow().reviewTargetCount()).isEqualTo(2);
            assertThat(query.findMemberSummaries(MemberModerationReviewStatus.REVIEW_REQUIRED, PageRequest.of(0, 20)))
                    .isEmpty();
            assertThat(query.findFlaggedEvidences(201L)).hasSize(2);
            assertThat(query.findFlaggedEvidences(202L)).hasSize(3)
                    .filteredOn(e -> !e.countedForReview()).hasSize(1);
        });
    }

    @Test
    void 실제_신고생성은_AI_결과없음_SAFE_FLAGGED_LOW와_독립적이다() {
        run(context -> {
            var room = context.getBean(ChatRoomRepository.class).save(ChatRoom.create(301L));
            var participants = context.getBean(ReservationParticipantRepository.class);
            var messages = context.getBean(ChatMessageRepository.class);
            var service = context.getBean(ChatRoomMemberReportService.class);
            var reports = context.getBean(ChatRoomMemberReportRepository.class);
            var query = context.getBean(MemberModerationQueryRepository.class);
            participants.save(ReservationParticipant.create(301L, 300L, 1));
            for (long target : List.of(301L, 302L, 303L)) {
                participants.save(ReservationParticipant.create(301L, target, 1));
                var message = messages.save(ChatMessage.create(room.getId(), target, target, "비식별 기술 fixture"));
                if (target != 301L) {
                    complete(context, message, target == 302L ? ModerationResultType.SAFE : ModerationResultType.FLAGGED,
                            RiskLevel.LOW);
                }

                var report = service.create(300L, room.getId(), target,
                        new ChatRoomMemberReportCreateRequest(ReportReason.ABUSE, message.getId(), null));

                assertThat(report.status()).isEqualTo(ReportStatus.PENDING);
                assertThat(reports.findById(report.reportId()).orElseThrow().getStatus()).isEqualTo(ReportStatus.PENDING);
                assertThat(query.findMemberSummary(target).map(s -> s.reviewTargetCount()).orElse(0L)).isZero();
                assertThat(reports.countByReportedMemberIdAndStatus(target, ReportStatus.PENDING)).isEqualTo(1);
            }
            assertThat(reports.count()).isEqualTo(3);
        });
    }

    @Test
    void 관리자_신고판단은_SAFE_결과나_AI_검토집계를_자동변경하지_않는다() {
        run(context -> {
            var room = context.getBean(ChatRoomRepository.class).save(ChatRoom.create(401L));
            var participants = context.getBean(ReservationParticipantRepository.class);
            participants.save(ReservationParticipant.create(401L, 400L, 1));
            participants.save(ReservationParticipant.create(401L, 401L, 1));
            var message = context.getBean(ChatMessageRepository.class)
                    .save(ChatMessage.create(room.getId(), 401L, 401L, "비식별 기술 fixture"));
            complete(context, message, ModerationResultType.SAFE, RiskLevel.LOW);
            var report = context.getBean(ChatRoomMemberReportService.class).create(400L, room.getId(), 401L,
                    new ChatRoomMemberReportCreateRequest(ReportReason.ABUSE, message.getId(), null));
            var admin = context.getBean(AdminModerationReportService.class);

            var before = admin.get(report.reportId());
            assertThat(before.context()).hasSize(1);
            assertThat(before.context().get(0).moderation().status()).isEqualTo(ModerationProcessingStatus.SAFE);
            var reviewed = admin.review(report.reportId(), 499L,
                    new AdminReportReviewRequest(ReviewDecision.VIOLATION_CONFIRMED));

            assertThat(reviewed.status()).isEqualTo(ReportStatus.REVIEWED);
            assertThat(context.getBean(ChatModerationRepository.class).findByMessageId(message.getId())
                    .orElseThrow().getStatus()).isEqualTo(ModerationProcessingStatus.SAFE);
            var after = admin.get(report.reportId());
            assertThat(after.moderationSignals().reviewTargetCount()).isZero();
            assertThat(after.reportSignals().confirmedViolationCount()).isEqualTo(1);
            assertThat(after.reportSignals().pendingReportCount()).isZero();
        });
    }

    private void run(Consumer<AssertableApplicationContext> assertions) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class))
                .withBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC))
                .withUserConfiguration(PersistenceConfiguration.class, JpaAuditingConfig.class,
                        MemberModerationQueryRepositoryImpl.class, ChatRoomMemberReportService.class,
                        AdminModerationReportService.class)
                .withPropertyValues("spring.datasource.url=jdbc:mysql://" + MYSQL.getHost() + ":"
                                + MYSQL.getMappedPort(3306) + "/issue68_review?useSSL=false&allowPublicKeyRetrieval=true",
                        "spring.datasource.username=root", "spring.datasource.password=issue68-fixture-only",
                        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "spring.jpa.hibernate.ddl-auto=create-drop")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertions.accept(context);
                });
    }

    private void save(AssertableApplicationContext context, long member, ModerationResultType result, RiskLevel risk) {
        var message = context.getBean(ChatMessageRepository.class)
                .save(ChatMessage.create(1L, member, member, "비식별 기술 fixture"));
        complete(context, message, result, risk);
    }

    private void complete(AssertableApplicationContext context, ChatMessage message, ModerationResultType result,
            RiskLevel risk) {
        // 각 Spring Data save의 별도 transaction이 반환된 후 Query를 실행한다. flush만으로 commit을 대신하지 않는다.
        context.getBean(ChatModerationRepository.class).save(ChatModeration.completed(message.getId(), result,
                result == ModerationResultType.SAFE ? Set.of() : Set.of(ModerationCategory.PROFANITY), risk,
                "FAKE_GATE_FIXTURE", "no-provider", ModerationPrompt.PROMPT_VERSION, ModerationPrompt.POLICY_VERSION,
                0L, null, null, null, NOW));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EntityScan("com.bobfull")
    @EnableJpaRepositories(basePackageClasses = {ChatMessageRepository.class, ReservationParticipantRepository.class},
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                    ChatMessageRepository.class, ChatModerationRepository.class, ChatRoomRepository.class,
                    ChatRoomMemberReportRepository.class, ReservationParticipantRepository.class}))
    static class PersistenceConfiguration {

        @Bean
        MyReservationQueryRepositoryImpl myReservationQueryRepositoryImpl(EntityManager entityManager) {
            return new MyReservationQueryRepositoryImpl(entityManager);
        }
    }
}
