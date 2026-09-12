package com.bobfull.notification.adapter;

import com.bobfull.reservation.application.port.ReservationNotificationPort;
import java.util.ArrayList;
import java.util.List;

/**
 * 실제 메일 서버 없이 발송 호출만 기록하는 테스트용 Fake Adapter다(Issue #168).
 * 실제 SMTP 연동 없이 예약 도메인의 알림 호출 시점·대상만 검증하고 싶은 테스트에서 사용한다.
 */
public class FakeReservationNotificationAdapter implements ReservationNotificationPort {
    private final List<ReservationResultNotification> confirmedNotifications = new ArrayList<>();
    private final List<ReservationResultNotification> cancelledNotifications = new ArrayList<>();
    private final List<ReservationResultNotification> reservationCreatedNotifications = new ArrayList<>();
    private final List<ReservationResultNotification> participationCompletedNotifications = new ArrayList<>();

    @Override
    public void notifyConfirmed(ReservationResultNotification notification) {
        confirmedNotifications.add(notification);
    }

    @Override
    public void notifyCancelledDueToInsufficientParticipants(ReservationResultNotification notification) {
        cancelledNotifications.add(notification);
    }

    @Override
    public void notifyReservationCreated(ReservationResultNotification notification) {
        reservationCreatedNotifications.add(notification);
    }

    @Override
    public void notifyParticipationCompleted(ReservationResultNotification notification) {
        participationCompletedNotifications.add(notification);
    }

    public List<ReservationResultNotification> confirmedNotifications() {
        return confirmedNotifications;
    }

    public List<ReservationResultNotification> cancelledNotifications() {
        return cancelledNotifications;
    }

    public List<ReservationResultNotification> reservationCreatedNotifications() {
        return reservationCreatedNotifications;
    }

    public List<ReservationResultNotification> participationCompletedNotifications() {
        return participationCompletedNotifications;
    }
}
