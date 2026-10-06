package com.supermarket.service;

import com.supermarket.dto.RefundRequest;
import com.supermarket.dto.RefundResponse;
import com.supermarket.dto.SaleForRefund;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.CartItem;
import com.supermarket.model.Cashier;
import com.supermarket.model.NumberSequence;
import com.supermarket.model.PaymentMethod;
import com.supermarket.model.Product;
import com.supermarket.model.Refund;
import com.supermarket.model.RefundItem;
import com.supermarket.model.Sale;
import com.supermarket.model.SaleItem;
import com.supermarket.model.Shift;
import com.supermarket.repository.NumberSequenceRepository;
import com.supermarket.repository.ProductRepository;
import com.supermarket.repository.RefundRepository;
import com.supermarket.repository.SaleRepository;
import com.supermarket.util.Quantities;
import com.supermarket.util.ReceiptLayout;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Refunds for earlier sales. Each refunded quantity gets back its share of what the customer actually paid
 * for that line (so a sale discount is returned proportionally), stock goes back on the shelf, and the money
 * leaves the current shift: from the drawer for cash, or recorded as a card refund.
 */
@Service
public class RefundService {

    private static final String REFUND_SEQUENCE = "REFUND";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final SaleRepository saleRepository;
    private final RefundRepository refundRepository;
    private final ProductRepository productRepository;
    private final NumberSequenceRepository numberSequenceRepository;
    private final ShiftService shiftService;
    private final ApprovalService approvalService;
    private final AuditService auditService;
    private final CustomerService customerService;
    private final MessageSource messageSource;
    private final ShopSettingsService shopSettingsService;

    public RefundService(SaleRepository saleRepository, RefundRepository refundRepository, ProductRepository productRepository,
                         NumberSequenceRepository numberSequenceRepository, ShiftService shiftService,
                         ApprovalService approvalService, AuditService auditService, CustomerService customerService,
                         MessageSource messageSource, ShopSettingsService shopSettingsService) {
        this.saleRepository = saleRepository;
        this.refundRepository = refundRepository;
        this.productRepository = productRepository;
        this.numberSequenceRepository = numberSequenceRepository;
        this.shiftService = shiftService;
        this.approvalService = approvalService;
        this.auditService = auditService;
        this.customerService = customerService;
        this.messageSource = messageSource;
        this.shopSettingsService = shopSettingsService;
    }

    @Transactional(readOnly = true)
    public SaleForRefund findSaleForRefund(String invoiceNumber) {
        Sale sale = saleRepository.findByInvoiceNumber(normalizeInvoiceNumber(invoiceNumber))
                .orElseThrow(() -> new ValidationException("refund.saleNotFound", invoiceNumber));
        Map<Long, BigDecimal[]> refunded = refundedSoFar(sale);
        List<SaleForRefund.Line> lines = sale.getItems().stream().map(item -> {
            BigDecimal done = refunded.getOrDefault(item.getId(), zeros())[0];
            return new SaleForRefund.Line(item.getId(), item.getProduct().getName(), item.getUnit(), item.getQuantity(), done,
                    item.getQuantity().subtract(done),
                    item.getLineTotal().divide(item.getQuantity(), 2, RoundingMode.HALF_UP), item.getLineTotal());
        }).toList();
        return new SaleForRefund(sale.getId(), sale.getInvoiceNumber(), sale.getDate(),
                sale.getCashier() == null ? null : sale.getCashier().getFullName(), sale.getTotalAmount(), lines);
    }

    @Transactional
    public RefundResponse refund(Cashier cashier, Long saleId, RefundRequest request, String approvalPin) {
        Sale sale = saleRepository.findById(saleId).orElseThrow(() -> new ValidationException("refund.saleNotFound", saleId));
        Shift shift = shiftService.findOpenShift(cashier.getId()).orElseThrow(() -> new ValidationException("refund.shiftRequired"));
        PaymentMethod method = parseMethod(request.method());
        if (method == PaymentMethod.POINTS) {
            throw new ValidationException("payment.invalidMethod");
        }
        if (method == PaymentMethod.CREDIT && sale.getCustomer() == null) {
            throw new ValidationException("refund.creditNeedsCustomer");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ValidationException("refund.reasonRequired");
        }
        List<RefundRequest.Line> lines = request.lines() == null ? List.of()
                : request.lines().stream().filter(line -> line.quantity() != null && line.quantity().signum() > 0).toList();
        if (lines.isEmpty()) {
            throw new ValidationException("refund.nothingSelected");
        }
        Cashier approver = approvalService.approve(cashier, approvalPin, "REFUND");

        Map<Long, SaleItem> saleItems = sale.getItems().stream().collect(Collectors.toMap(SaleItem::getId, Function.identity()));
        Map<Long, BigDecimal[]> refunded = refundedSoFar(sale);
        Map<Long, Product> products = productRepository
                .findAllForUpdate(lines.stream().map(line -> requireItem(saleItems, line).getProduct().getId()).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));

        Refund refund = new Refund(sale, shift, cashier, approver, method, request.reason().trim(), LocalDateTime.now());
        for (RefundRequest.Line line : lines) {
            SaleItem item = requireItem(saleItems, line);
            BigDecimal quantity = Quantities.requirePositive(line.quantity(), item.getUnit());
            BigDecimal[] done = refunded.getOrDefault(item.getId(), zeros());
            BigDecimal remaining = item.getQuantity().subtract(done[0]);
            if (quantity.compareTo(remaining) > 0) {
                throw new ValidationException("refund.tooMuch", item.getProduct().getName(), remaining.stripTrailingZeros().toPlainString());
            }
            // The last part of a line gets exactly what is left, so rounding never refunds more than was paid.
            BigDecimal amount = quantity.compareTo(remaining) == 0
                    ? item.getLineTotal().subtract(done[1])
                    : item.getLineTotal().multiply(quantity).divide(item.getQuantity(), 2, RoundingMode.HALF_UP);
            BigDecimal tax = amount.subtract(CartItem.withoutTax(amount, item.getTaxRate()));
            refund.addItem(new RefundItem(item, quantity, amount, tax));
            done[0] = done[0].add(quantity);
            done[1] = done[1].add(amount);
            refunded.put(item.getId(), done);
            Product product = products.get(item.getProduct().getId());
            product.setStock(product.getStock().add(quantity));
        }

        NumberSequence sequence = numberSequenceRepository.findForUpdate(REFUND_SEQUENCE)
                .orElseThrow(() -> new IllegalStateException("Refund number sequence is missing"));
        refund.setRefundNumber(String.format("R%06d", sequence.next()));
        Refund saved = refundRepository.save(refund);
        if (sale.getCustomer() != null) {
            // Points earned on the sale go back in proportion to what is refunded.
            int pointsBack = sale.getTotalAmount().signum() == 0 ? 0 : BigDecimal.valueOf(sale.getPointsEarned())
                    .multiply(saved.getTotalAmount()).divide(sale.getTotalAmount(), 0, RoundingMode.DOWN).intValue();
            customerService.recordRefund(sale.getCustomer(), cashier, saved, pointsBack,
                    method == PaymentMethod.CREDIT ? saved.getTotalAmount() : BigDecimal.ZERO);
        }
        auditService.record(cashier, approver, "REFUND", "SALE", sale.getInvoiceNumber(),
                saved.getRefundNumber() + ": " + saved.getTotalAmount() + " LEK " + method + " - " + saved.getReason());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> findForSale(Long saleId) {
        return refundRepository.findForSale(saleId).stream().map(this::toResponse).toList();
    }

    private RefundResponse toResponse(Refund refund) {
        return new RefundResponse(refund.getId(), refund.getRefundNumber(), refund.getInvoiceNumber(), refund.getCreatedAt(),
                refund.getMethod().name(), refund.getTotalAmount(), refund.getReason(), refund.getCashier().getFullName(),
                refund.getApprovedBy().getFullName(), List.copyOf(refund.getItems()), printable(refund));
    }

    private String printable(Refund refund) {
        Locale locale = LocaleContextHolder.getLocale();
        ReceiptLayout r = shopSettingsService.header();
        r.center(text("refund.title", locale));
        r.row(text("refund.number", locale), refund.getRefundNumber());
        r.row(text("refund.forInvoice", locale), refund.getInvoiceNumber());
        r.row(text("receipt.date", locale), refund.getCreatedAt().format(DATE_TIME));
        r.row(text("receipt.cashier", locale), refund.getCashier().getFullName());
        if (!refund.getApprovedBy().getId().equals(refund.getCashier().getId())) {
            r.row(text("refund.approvedBy", locale), refund.getApprovedBy().getFullName());
        }
        r.rule();
        for (RefundItem item : refund.getItems()) {
            r.line(item.getProductName());
            r.row("  " + item.getQuantity().stripTrailingZeros().toPlainString() + " " + text("unit." + item.getUnit(), locale),
                    "-" + ReceiptLayout.format(item.getAmount()));
        }
        r.rule();
        r.row(text("refund.total", locale) + " LEK", "-" + ReceiptLayout.format(refund.getTotalAmount()));
        r.line(text("payment." + refund.getMethod().name().toLowerCase(), locale));
        r.line(text("refund.reason", locale) + ": " + refund.getReason());
        r.rule();
        return r.toString();
    }

    /** Per sale line: [quantity already refunded, amount already refunded]. */
    private Map<Long, BigDecimal[]> refundedSoFar(Sale sale) {
        Map<Long, BigDecimal[]> refunded = new HashMap<>();
        for (Refund earlier : refundRepository.findForSale(sale.getId())) {
            for (RefundItem item : earlier.getItems()) {
                BigDecimal[] sums = refunded.computeIfAbsent(item.getSaleItemId(), id -> zeros());
                sums[0] = sums[0].add(item.getQuantity());
                sums[1] = sums[1].add(item.getAmount());
            }
        }
        return refunded;
    }

    private static SaleItem requireItem(Map<Long, SaleItem> saleItems, RefundRequest.Line line) {
        SaleItem item = line.saleItemId() == null ? null : saleItems.get(line.saleItemId());
        if (item == null) {
            throw new ValidationException("refund.itemNotInSale");
        }
        return item;
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private static PaymentMethod parseMethod(String method) {
        try {
            return PaymentMethod.valueOf(method == null ? "" : method.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("payment.invalidMethod");
        }
    }

    /** Accepts "123" as well as "000123". */
    private static String normalizeInvoiceNumber(String number) {
        String trimmed = number == null ? "" : number.trim();
        return trimmed.matches("\\d{1,6}") ? String.format("%06d", Long.parseLong(trimmed)) : trimmed;
    }

    private String text(String code, Locale locale) {
        return messageSource.getMessage(code, null, code, locale);
    }
}
