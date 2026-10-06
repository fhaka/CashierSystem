package com.supermarket.dto;

import java.math.BigDecimal;
import java.util.List;

/** Which sale lines go back and how much of each; method is CASH (from the drawer) or CARD. */
public record RefundRequest(List<Line> lines, String method, String reason) {

    public record Line(Long saleItemId, BigDecimal quantity) {
    }
}
