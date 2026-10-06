package com.supermarket.dto;

import java.math.BigDecimal;

public class ShiftCloseRequest {

    private BigDecimal closingCash;

    public BigDecimal getClosingCash() {
        return closingCash;
    }

    public void setClosingCash(BigDecimal closingCash) {
        this.closingCash = closingCash;
    }
}
