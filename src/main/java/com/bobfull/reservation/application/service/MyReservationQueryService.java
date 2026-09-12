package com.bobfull.reservation.application.service;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.ReservationErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.reservation.presentation.dto.MyReservationDetailResponse;
import com.bobfull.reservation.presentation.dto.MyReservationListItemResponse;
import com.bobfull.reservation.application.dto.MyReservationResult;
import com.bobfull.reservation.domain.entity.ReservationStatus;
import com.bobfull.reservation.infrastructure.repository.ReservationParticipantRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인한 회원 본인이 최초 예약자이거나 참여(JOIN)한 예약의 목록·상세를 조회한다(Issue #124).
 * 인가는 ReservationParticipant.memberId 기준으로만 판단하며, 다른 회원의 reservationId는
 * 존재 여부를 노출하지 않기 위해 404로 응답한다.
 */
@Service
public class MyReservationQueryService {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private final ReservationParticipantRepository reservationParticipantRepository;

    public MyReservationQueryService(ReservationParticipantRepository reservationParticipantRepository) {
        this.reservationParticipantRepository = reservationParticipantRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<MyReservationListItemResponse> getMyReservations(
            Long memberId, String reservationStatus, Pageable pageable
    ) {
        ReservationStatus status = parseReservationStatus(reservationStatus);
        Page<MyReservationResult> results = reservationParticipantRepository
                .searchMyReservations(memberId, status, pageable);
        return PageResponse.from(results.map(this::toListItem));
    }

    @Transactional(readOnly = true)
    public MyReservationDetailResponse getMyReservationDetail(Long memberId, Long reservationId) {
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
