package com.bobfull.reservation.application.service;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.reservation.domain.exception.ReservationErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.reservation.presentation.response.MyReservationDetailResponse;
import com.bobfull.reservation.presentation.response.MyReservationListItemResponse;
import com.bobfull.reservation.application.result.MyReservationResult;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 로그인 회원이 참여한 예약의 목록과 상세를 조회한다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyReservationQueryService {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final ReservationParticipantRepository reservationParticipantRepository;

    public PageResponse<MyReservationListItemResponse> getMyReservations(
            Long memberId, String reservationStatus, Pageable pageable
    ) {
        ReservationStatus status = parseReservationStatus(reservationStatus);
        Page<MyReservationResult> results = reservationParticipantRepository
                .searchMyReservations(memberId, status, pageable);
        return PageResponse.from(results.map(this::toListItem));
    }

    public MyReservationDetailResponse getMyReservationDetail(Long memberId, Long reservationId) {
        // 다른 회원의 예약 존재 여부가 드러나지 않도록 참여 관계가 없으면 NOT_FOUND로 처리한다.
        MyReservationResult result = reservationParticipantRepository
                .findMyReservationDetail(memberId, reservationId)
                .orElseThrow(() -> new CustomException(ReservationErrorCode.RESERVATION_ID_NOT_FOUND));
        return MyReservationDetailResponse.of(result, toSeoulOffset(result.startAt()), toSeoulOffset(result.endAt()));
    }

    private ReservationStatus parseReservationStatus(String reservationStatus) {
        if (reservationStatus == null || reservationStatus.isBlank()) {
            return null;
        }
        try {
            return ReservationStatus.valueOf(reservationStatus);
        } catch (IllegalArgumentException exception) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private MyReservationListItemResponse toListItem(MyReservationResult result) {
        return MyReservationListItemResponse.of(result, toSeoulOffset(result.startAt()), toSeoulOffset(result.endAt()));
    }

    private OffsetDateTime toSeoulOffset(Instant instant) {
        return instant.atZone(SEOUL_ZONE).toOffsetDateTime();
    }
}
