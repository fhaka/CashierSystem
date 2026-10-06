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
    private static final String RULE = "--------------------------------\n";

    private final SaleRepository saleRepository;
    private final RefundRepository refundRepository;
    private final ProductRepository productRepository;
    private final NumberSequenceRepository numberSequenceRepository;
    private final ShiftService shiftService;
    private final ApprovalService approvalService;
    private final AuditService auditService;
    private final MessageSource messageSource;

    public RefundService(SaleRepository saleRepository, RefundRepository refundRepository, ProductRepository productRepository,
                         NumberSequenceRepository numberSequenceRepository, ShiftService shiftService,
                         ApprovalService approvalService, AuditService auditService, MessageSource messageSource) {
        this.saleRepository = saleRepository;
        this.refundRepository = refundRepository;
        this.productRepository = productRepository;
        this.numberSequenceRepository = numberSequenceRepository;
        this.shiftService = shiftService;
        this.approvalService = approvalService;
        this.auditService = auditService;
        this.messageSource = messageSource;
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
        StringBuilder text = new StringBuilder();
        text.append(text("refund.title", locale)).append("\n");
        text.append(text("refund.number", locale)).append(": ").append(refund.getRefundNumber()).append("\n");
        text.append(text("refund.forInvoice", locale)).append(": ").append(refund.getInvoiceNumber()).append("\n");
        text.append(text("receipt.date", locale)).append(": ").append(refund.getCreatedAt().format(DATE_TIME)).append("\n");
        text.append(text("receipt.cashier", locale)).append(": ").append(refund.getCashier().getFullName()).append("\n");
        if (!refund.getApprovedBy().getId().equals(refund.getCashier().getId())) {
            text.append(text("refund.approvedBy", locale)).append(": ").append(refund.getApprovedBy().getFullName()).append("\n");
        }
        text.append(RULE);
        for (RefundItem item : refund.getItems()) {
            text.append(item.getProductName()).append("\n  ")
                    .append(item.getQuantity().stripTrailingZeros().toPlainString()).append(" ")
                    .append(text("unit." + item.getUnit(), locale)).append(" = -").append(item.getAmount()).append("\n");
        }
        text.append(RULE);
        text.append(text("refund.total", locale)).append(": -").append(refund.getTotalAmount()).append(" LEK\n");
        text.append(text("payment." + refund.getMethod().name().toLowerCase(), locale)).append("\n");
        text.append(text("refund.reason", locale)).append(": ").append(refund.getReason()).append("\n");
        text.append(RULE);
        return text.toString();
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
