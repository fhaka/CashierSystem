package com.supermarket.service;

import com.supermarket.dto.PeriodReport;
import com.supermarket.dto.ReceiptResponse;
import com.supermarket.model.CashMovement;
import com.supermarket.model.PaymentMethod;
import com.supermarket.model.Refund;
import com.supermarket.model.RefundItem;
import com.supermarket.model.Sale;
import com.supermarket.model.SaleItem;
import com.supermarket.model.SalePayment;
import com.supermarket.model.Shift;
import com.supermarket.repository.CashMovementRepository;
import com.supermarket.repository.RefundRepository;
import com.supermarket.repository.SaleRepository;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** End-of-shift (X) and end-of-day (Z) reports, as data and as printable text for the receipt printer. */
@Service
public class ReportService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String RULE = "--------------------------------\n";

    private final SaleRepository saleRepository;
    private final CashMovementRepository cashMovementRepository;
    private final RefundRepository refundRepository;
    private final MessageSource messageSource;

    public ReportService(SaleRepository saleRepository, CashMovementRepository cashMovementRepository,
                         RefundRepository refundRepository, MessageSource messageSource) {
        this.saleRepository = saleRepository;
        this.cashMovementRepository = cashMovementRepository;
        this.refundRepository = refundRepository;
        this.messageSource = messageSource;
    }

    @Transactional(readOnly = true)
    public PeriodReport shiftReport(Shift shift) {
        List<Sale> sales = saleRepository.findByShiftIdOrderByDate(shift.getId());
        List<CashMovement> movements = cashMovementRepository.findByShiftIdOrderByCreatedAt(shift.getId());
        LocalDateTime to = shift.getClosedAt() == null ? LocalDateTime.now() : shift.getClosedAt();
        return build("X", shift, shift.getOpenedAt(), to, sales, movements, refundRepository.findForShift(shift.getId()));
    }

    @Transactional(readOnly = true)
    public PeriodReport dailyReport(LocalDate day) {
        LocalDateTime from = day.atStartOfDay();
        LocalDateTime to = day.atTime(LocalTime.MAX);
        return build("Z", null, from, to,
                saleRepository.findByDateBetweenOrderByDate(from, to),
                cashMovementRepository.findByCreatedAtBetweenOrderByCreatedAt(from, to),
                refundRepository.findByCreatedAtBetweenOrderByCreatedAt(from, to));
    }

    private PeriodReport build(String type, Shift shift, LocalDateTime from, LocalDateTime to,
                               List<Sale> sales, List<CashMovement> movements, List<Refund> refunds) {
        CashTotals totals = CashTotals.of(sales, movements, refunds);
        BigDecimal discounts = sales.stream().map(Sale::getDiscountAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<BigDecimal, BigDecimal[]> vatByRate = new TreeMap<>();
        Map<String, BigDecimal[]> cashByCurrency = new TreeMap<>();
        Map<String, Object[]> byCashier = new LinkedHashMap<>();
        for (Sale sale : sales) {
            for (SaleItem item : sale.getItems()) {
                BigDecimal[] vat = vatByRate.computeIfAbsent(item.getTaxRate().setScale(2, RoundingMode.HALF_UP),
                        rate -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                vat[0] = vat[0].add(item.getLineTotal());
                vat[1] = vat[1].add(item.getTaxAmount());
            }
            for (SalePayment payment : sale.getPayments()) {
                if (payment.getMethod() == PaymentMethod.CASH) {
                    BigDecimal[] cash = cashByCurrency.computeIfAbsent(payment.getCurrency(),
                            currency -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                    cash[0] = cash[0].add(payment.getAmount());
                    cash[1] = cash[1].add(payment.getAmountLek());
                }
            }
            String cashier = sale.getCashier() == null ? "-" : sale.getCashier().getFullName();
            Object[] line = byCashier.computeIfAbsent(cashier, name -> new Object[] {0, BigDecimal.ZERO});
            line[0] = (int) line[0] + 1;
            line[1] = ((BigDecimal) line[1]).add(sale.getTotalAmount());
        }

        // Refunds give VAT back: the VAT summary is net of them.
        for (Refund refund : refunds) {
            for (RefundItem item : refund.getItems()) {
                BigDecimal[] vat = vatByRate.computeIfAbsent(item.getTaxRate().setScale(2, RoundingMode.HALF_UP),
                        rate -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
                vat[0] = vat[0].subtract(item.getAmount());
                vat[1] = vat[1].subtract(item.getTaxAmount());
            }
        }

        List<ReceiptResponse.VatLine> vat = new ArrayList<>();
        vatByRate.forEach((rate, sums) -> vat.add(new ReceiptResponse.VatLine(rate, sums[0].subtract(sums[1]), sums[1], sums[0])));
        List<PeriodReport.CurrencyLine> currencies = new ArrayList<>();
        cashByCurrency.forEach((currency, sums) -> currencies.add(new PeriodReport.CurrencyLine(currency, sums[0], sums[1])));
        List<PeriodReport.CashierLine> cashiers = new ArrayList<>();
        byCashier.forEach((name, line) -> cashiers.add(new PeriodReport.CashierLine(name, (int) line[0], (BigDecimal) line[1])));

        BigDecimal opening = shift == null ? null : shift.getOpeningCash();
        BigDecimal expected = shift == null ? null : totals.expectedCash(opening);
        PeriodReport data = new PeriodReport(type, shift == null ? null : shift.getId(), from, to, sales.size(),
                totals.totalSales().add(discounts), discounts, totals.totalSales(), vat, currencies,
                totals.cashReceived(), totals.changeGiven(), totals.cashSales(), totals.cardSales(),
                totals.cashIn(), totals.cashOut(), refunds.size(), totals.cashRefunds(), totals.cardRefunds(),
                totals.totalSales().subtract(totals.totalRefunds()), opening, expected, cashiers, null);
        return withText(data, shift);
    }

    private PeriodReport withText(PeriodReport r, Shift shift) {
        Locale locale = LocaleContextHolder.getLocale();
        StringBuilder text = new StringBuilder();
        if (shift != null) {
            text.append(text("report.x.title", locale, shift.getId())).append("\n");
            text.append(text("receipt.cashier", locale)).append(": ").append(shift.getCashier().getFullName()).append("\n");
            text.append(text("report.from", locale)).append(": ").append(r.from().format(DATE_TIME)).append("\n");
            text.append(text("report.to", locale)).append(": ")
                    .append(shift.getClosedAt() == null ? text("report.stillOpen", locale) : r.to().format(DATE_TIME)).append("\n");
        } else {
            text.append(text("report.z.title", locale)).append("\n");
            text.append(text("receipt.date", locale)).append(": ").append(r.from().format(DATE)).append("\n");
        }
        text.append(text("report.printed", locale)).append(": ").append(LocalDateTime.now().format(DATE_TIME)).append("\n");
        text.append(RULE);
        line(text, text("report.salesCount", locale), String.valueOf(r.salesCount()));
        line(text, text("report.gross", locale), r.grossSales());
        line(text, text("report.discounts", locale), r.discounts().negate());
        line(text, text("receipt.total", locale), r.totalSales());
        if (r.refundsCount() > 0) {
            line(text, text("report.refunds", locale) + " (" + r.refundsCount() + ")",
                    r.cashRefunds().add(r.cardRefunds()).negate());
            line(text, text("report.netSales", locale), r.netSales());
        }
        text.append(RULE);
        text.append(text("receipt.vatSummary", locale)).append("\n");
        for (ReceiptResponse.VatLine vat : r.vat()) {
            text.append(text("receipt.tax", locale)).append(" ").append(vat.taxRate().stripTrailingZeros().toPlainString())
                    .append("%: ").append(text("receipt.vatBase", locale)).append(" ").append(vat.netAmount())
                    .append(", ").append(text("receipt.tax", locale)).append(" ").append(vat.taxAmount()).append("\n");
        }
        text.append(RULE);
        text.append(text("report.payments", locale)).append("\n");
        for (PeriodReport.CurrencyLine cash : r.cashByCurrency()) {
            text.append(text("payment.cash", locale)).append(" ").append(cash.currency()).append(": ").append(cash.amount());
            if (!ExchangeRateService.HOME_CURRENCY.equals(cash.currency())) {
                text.append(" (").append(cash.amountLek()).append(" LEK)");
            }
            text.append("\n");
        }
        line(text, text("report.changeGiven", locale), r.changeGiven().negate());
        line(text, text("report.cashNet", locale), r.cashSales());
        line(text, text("payment.card", locale), r.cardSales());
        text.append(RULE);
        line(text, text("report.cashIn", locale), r.cashIn());
        line(text, text("report.cashOut", locale), r.cashOut().negate());
        if (r.refundsCount() > 0) {
            line(text, text("report.cashRefunds", locale), r.cashRefunds().negate());
            line(text, text("report.cardRefunds", locale), r.cardRefunds().negate());
        }
        if (r.openingCash() != null) {
            line(text, text("report.openingCash", locale), r.openingCash());
            line(text, text("report.expectedCash", locale), r.expectedCash());
        }
        if (shift == null && !r.byCashier().isEmpty()) {
            text.append(RULE);
            text.append(text("report.byCashier", locale)).append("\n");
            for (PeriodReport.CashierLine cashier : r.byCashier()) {
                text.append(cashier.cashierName()).append(": ").append(cashier.salesCount()).append(" x, ")
                        .append(cashier.total()).append("\n");
            }
        }
        text.append(RULE);
        return new PeriodReport(r.type(), r.shiftId(), r.from(), r.to(), r.salesCount(), r.grossSales(), r.discounts(),
                r.totalSales(), r.vat(), r.cashByCurrency(), r.cashReceived(), r.changeGiven(), r.cashSales(), r.cardSales(),
                r.cashIn(), r.cashOut(), r.refundsCount(), r.cashRefunds(), r.cardRefunds(), r.netSales(),
                r.openingCash(), r.expectedCash(), r.byCashier(), text.toString());
    }

    private static void line(StringBuilder text, String label, Object value) {
        Object shown = value instanceof BigDecimal amount ? amount.setScale(2, RoundingMode.HALF_UP) : value;
        text.append(label).append(": ").append(shown).append("\n");
    }

    private String text(String code, Locale locale, Object... args) {
        Object[] values = args.length == 0 ? null : java.util.Arrays.stream(args).map(String::valueOf).toArray();
        return messageSource.getMessage(code, values, code, locale);
    }
}
