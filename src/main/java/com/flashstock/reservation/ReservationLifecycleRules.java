package com.flashstock.reservation;

import com.flashstock.api.ApiException;
import java.time.Instant;

public final class ReservationLifecycleRules {
    private ReservationLifecycleRules() { }

    public static ConfirmationAction confirmationAction(ReservationStatus status, Instant expiresAt, Instant now) {
        if (status == ReservationStatus.CONFIRMED) return ConfirmationAction.RETURN_EXISTING_ORDER;
        if (status == ReservationStatus.PENDING) return expiresAt.isAfter(now) ? ConfirmationAction.CREATE_ORDER : ConfirmationAction.EXPIRE;
        throw ApiException.invalidState("RESERVATION_NOT_CONFIRMABLE", "Reservation is already " + status);
    }

    public static CancellationAction cancellationAction(ReservationStatus status) {
        return switch (status) {
            case PENDING -> CancellationAction.RELEASE_INVENTORY;
            case CANCELLED, EXPIRED -> CancellationAction.RETURN_TERMINAL_STATE;
            case CONFIRMED -> throw ApiException.invalidState("RESERVATION_NOT_CANCELLABLE", "Confirmed reservation cannot be cancelled");
        };
    }

    public enum ConfirmationAction { CREATE_ORDER, EXPIRE, RETURN_EXISTING_ORDER }
    public enum CancellationAction { RELEASE_INVENTORY, RETURN_TERMINAL_STATE }
}
