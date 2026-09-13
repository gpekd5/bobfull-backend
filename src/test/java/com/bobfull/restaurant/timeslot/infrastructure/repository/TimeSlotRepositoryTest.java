package com.bobfull.restaurant.timeslot.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bobfull.restaurant.timeslot.domain.entity.TimeSlot;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:time-slot-repository-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TimeSlotRepositoryTest {

    private static final Long TABLE_ID = 100L;
    private static final Instant START_AT = Instant.parse("2026-08-01T02:00:00Z");
    private static final Instant END_AT = Instant.parse("2026-08-01T04:00:00Z");

    @Autowired
    private TimeSlotRepository timeSlotRepository;

    @Test
    void 활성_회차는_같은_테이블과_시작_시각으로_중복_저장할_수_없다() {
        // given
        timeSlotRepository.saveAndFlush(TimeSlot.create(TABLE_ID, START_AT, END_AT));

        // when & then
        assertThatThrownBy(() -> timeSlotRepository.saveAndFlush(TimeSlot.create(TABLE_ID, START_AT, END_AT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 삭제된_회차와_같은_테이블과_시작_시각은_다시_저장할_수_있다() {
        // given
        TimeSlot deletedTimeSlot = timeSlotRepository.saveAndFlush(TimeSlot.create(TABLE_ID, START_AT, END_AT));
        deletedTimeSlot.softDelete(Instant.parse("2026-07-29T00:00:00Z"));
        timeSlotRepository.flush();

        // when
        TimeSlot recreatedTimeSlot = timeSlotRepository.saveAndFlush(TimeSlot.create(TABLE_ID, START_AT, END_AT));

        // then
        assertThat(recreatedTimeSlot.getId()).isNotNull();
    }
}
