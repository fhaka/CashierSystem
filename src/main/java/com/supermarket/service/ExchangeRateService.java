package com.supermarket.service;

import com.supermarket.dto.ExchangeRateRequest;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.Cashier;
import com.supermarket.model.ExchangeRate;
import com.supermarket.repository.ExchangeRateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Exchange rates are kept on the server so every till converts foreign cash the same way. */
@Service
public class ExchangeRateService {

    public static final String HOME_CURRENCY = "LEK";

    private final ExchangeRateRepository exchangeRateRepository;
    private final AuditService auditService;

    public ExchangeRateService(ExchangeRateRepository exchangeRateRepository, AuditService auditService) {
        this.exchangeRateRepository = exchangeRateRepository;
        this.auditService = auditService;
    }

    public List<ExchangeRate> findAll() {
        return exchangeRateRepository.findAll();
    }

    /** LEK received for 1 unit of the currency when a customer pays with it. */
    public BigDecimal buyRate(String currency) {
        if (HOME_CURRENCY.equals(currency)) {
            return BigDecimal.ONE;
        }
        return exchangeRateRepository.findById(currency)
                .map(ExchangeRate::getBuyRate)
                .orElseThrow(() -> new ValidationException("payment.invalidCurrency", currency));
    }

    @Transactional
    public List<ExchangeRate> update(List<ExchangeRateRequest> requests, Cashier actor) {
        LocalDateTime now = LocalDateTime.now();
        for (ExchangeRateRequest request : requests == null ? List.<ExchangeRateRequest>of() : requests) {
            ExchangeRate rate = exchangeRateRepository.findById(normalize(request.currency()))
                    .orElseThrow(() -> new ValidationException("payment.invalidCurrency", request.currency()));
            if (request.buyRate() == null || request.buyRate().signum() <= 0
                    || request.sellRate() == null || request.sellRate().signum() <= 0) {
                throw new ValidationException("rate.positive");
            }
            if (rate.getBuyRate().compareTo(request.buyRate()) != 0 || rate.getSellRate().compareTo(request.sellRate()) != 0) {
                auditService.record(actor, "EXCHANGE_RATE_UPDATED", "CURRENCY", rate.getCurrency(),
                        "buy " + rate.getBuyRate().stripTrailingZeros().toPlainString() + " -> " + request.buyRate().stripTrailingZeros().toPlainString()
                                + ", sell " + rate.getSellRate().stripTrailingZeros().toPlainString() + " -> " + request.sellRate().stripTrailingZeros().toPlainString());
            }
            rate.update(request.buyRate(), request.sellRate(), now);
        }
        return findAll();
    }

    public static String normalize(String currency) {
        if (currency == null || currency.isBlank()) {
            return HOME_CURRENCY;
        }
        String code = currency.trim().toUpperCase();
        return "EURO".equals(code) ? "EUR" : code;
    }
}
