package com.supermarket.dto;

import java.math.BigDecimal;

public record ExchangeRateRequest(String currency, BigDecimal buyRate, BigDecimal sellRate) {
}
