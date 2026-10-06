package com.supermarket.dto;

import java.math.BigDecimal;

public class ShiftOpenRequest {

    private Long cashierId;
    private BigDecimal openingCash;

    public Long getCashierId() {
        return cashierId;
    }

    public void setCashierId(Long cashierId) {
        this.cashierId = cashierId;
    }

    public BigDecimal getOpeningCash() {
        return openingCash;
    }

    public void setOpeningCash(BigDecimal openingCash) {
        this.openingCash = openingCash;
    }
}
