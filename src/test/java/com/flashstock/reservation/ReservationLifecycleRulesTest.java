package com.flashstock.reservation;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationLifecycleRulesTest {
    private final Instant now = Instant.parse("2026-10-14T12:00:00Z");

    @Test void pendingReservationCanBeConfirmedBeforeItsExpiry() {
        assertThat(ReservationLifecycleRules.confirmationAction(ReservationStatus.PENDING, now.plusSeconds(1), now))
                .isEqualTo(ReservationLifecycleRules.ConfirmationAction.CREATE_ORDER);
    }

    @Test void pendingReservationAtExpiryMustExpireInsteadOfConfirming() {
        assertThat(ReservationLifecycleRules.confirmationAction(ReservationStatus.PENDING, now, now))
                .isEqualTo(ReservationLifecycleRules.ConfirmationAction.EXPIRE);
    }

    @Test void confirmingAnAlreadyConfirmedReservationReturnsItsOriginalOrder() {
        assertThat(ReservationLifecycleRules.confirmationAction(ReservationStatus.CONFIRMED, now.minusSeconds(1), now))
                .isEqualTo(ReservationLifecycleRules.ConfirmationAction.RETURN_EXISTING_ORDER);
    }

    @Test void cancelledOrExpiredReservationsCannotBeConfirmed() {
        for (ReservationStatus status : new ReservationStatus[]{ReservationStatus.CANCELLED, ReservationStatus.EXPIRED}) {
            assertThatThrownBy(() -> ReservationLifecycleRules.confirmationAction(status, now.plusSeconds(60), now))
                    .hasMessageContaining(status.toString());
        }
    }

    @Test void onlyPendingReservationsReleaseStockOnCancellation() {
        assertThat(ReservationLifecycleRules.cancellationAction(ReservationStatus.PENDING))
                .isEqualTo(ReservationLifecycleRules.CancellationAction.RELEASE_INVENTORY);
        assertThat(ReservationLifecycleRules.cancellationAction(ReservationStatus.CANCELLED))
                .isEqualTo(ReservationLifecycleRules.CancellationAction.RETURN_TERMINAL_STATE);
        assertThat(ReservationLifecycleRules.cancellationAction(ReservationStatus.EXPIRED))
                .isEqualTo(ReservationLifecycleRules.CancellationAction.RETURN_TERMINAL_STATE);
        assertThatThrownBy(() -> ReservationLifecycleRules.cancellationAction(ReservationStatus.CONFIRMED))
                .hasMessageContaining("cannot be cancelled");
    }
}
