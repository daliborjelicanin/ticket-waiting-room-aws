package com.ticketwaitingroom.checkoutpayment.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

/**
 * One seat on an order, paired with the hold that reserved it in DynamoDB.
 *
 * <p>The {@code holdId} is not decoration: it is what the saga's compensating action needs
 * to release <em>exactly</em> the holds this order took. Releasing by seat id alone would
 * risk releasing a hold someone else has since acquired on the same seat.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderSeat {

    @Column(name = "seat_id", nullable = false)
    private String seatId;

    @Column(name = "hold_id", nullable = false)
    private String holdId;

    public OrderSeat(String seatId, String holdId) {
        this.seatId = Objects.requireNonNull(seatId, "seatId");
        this.holdId = Objects.requireNonNull(holdId, "holdId");
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OrderSeat seat
                && seatId.equals(seat.seatId)
                && holdId.equals(seat.holdId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seatId, holdId);
    }
}
