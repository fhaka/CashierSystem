package com.supermarket.dto;

import java.math.BigDecimal;

public class ShiftCloseRequest {

    private BigDecimal closingCash;

    /** The cashier confirmed that products still in the till's tabs are thrown away (not sold). */
    private boolean discardCarts;

    public BigDecimal getClosingCash() {
        return closingCash;
    }

    public void setClosingCash(BigDecimal closingCash) {
        this.closingCash = closingCash;
    }

    public boolean isDiscardCarts() {
        return discardCarts;
    }

    public void setDiscardCarts(boolean discardCarts) {
        this.discardCarts = discardCarts;
    }
}
