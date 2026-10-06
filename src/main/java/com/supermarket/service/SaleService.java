package com.supermarket.service;

import com.supermarket.dto.CartSummary;
import com.supermarket.dto.CheckoutRequest;
import com.supermarket.dto.ReceiptItemResponse;
import com.supermarket.dto.ReceiptResponse;
import com.supermarket.dto.SaleLogRequest;
import com.supermarket.exception.InsufficientStockException;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.Cart;
import com.supermarket.model.CartItem;
import com.supermarket.model.Cashier;
import com.supermarket.model.NumberSequence;
import com.supermarket.model.PaymentMethod;
import com.supermarket.model.Product;
import com.supermarket.model.Sale;
import com.supermarket.model.SaleItem;
import com.supermarket.model.SalePayment;
import com.supermarket.model.Shift;
import com.supermarket.repository.JdbcLogRepository;
import com.supermarket.repository.NumberSequenceRepository;
import com.supermarket.repository.ProductRepository;
import com.supermarket.repository.SaleRepository;
import com.supermarket.strategy.PricingStrategy;
import com.supermarket.util.Quantities;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SaleService {

    private static final DateTimeFormatter RECEIPT_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final String INVOICE_SEQUENCE = "SALE_INVOICE";
    private static final String RULE = "--------------------------------\n";

    private final CartService cartService;
    private final ProductRepository productRepository;
    private final SaleRepository saleRepository;
    private final NumberSequenceRepository numberSequenceRepository;
    private final JdbcLogRepository jdbcLogRepository;
    private final ShiftService shiftService;
    private final List<PricingStrategy> pricingStrategies;
    private final MessageSource messageSource;
    private final ExchangeRateService exchangeRateService;

    public SaleService(
            CartService cartService,
            ProductRepository productRepository,
            SaleRepository saleRepository,
            NumberSequenceRepository numberSequenceRepository,
            JdbcLogRepository jdbcLogRepository,
            ShiftService shiftService,
            List<PricingStrategy> pricingStrategies,
            MessageSource messageSource,
            ExchangeRateService exchangeRateService
    ) {
        this.cartService = cartService;
        this.productRepository = productRepository;
        this.saleRepository = saleRepository;
        this.numberSequenceRepository = numberSequenceRepository;
        this.jdbcLogRepository = jdbcLogRepository;
        this.shiftService = shiftService;
        this.pricingStrategies = pricingStrategies;
        this.messageSource = messageSource;
        this.exchangeRateService = exchangeRateService;
    }

    public List<Sale> findAll() {
        return saleRepository.findAllByOrderByDateDesc();
    }

    public List<Sale> findForCashier(Long cashierId) {
        return saleRepository.findByCashierIdOrderByDateDesc(cashierId);
    }

    /** Total of the cart on screen, using the same pricing rules as checkout, for the payment window. */
    @Transactional(readOnly = true)
    public CartSummary cartSummary(Cashier cashier) {
        List<CartItem> items = cartService.getCart(cashier);
        BigDecimal subtotal = cartService.calculateSubtotal(cashier);
        BigDecimal total = applyPricingStrategies(items, subtotal);
        return new CartSummary(subtotal, subtotal.subtract(total), total);
    }

    /** The number the next sale will most likely get. Only a preview: another till may take it first. */
    public String peekNextInvoiceNumber() {
        long last = numberSequenceRepository.findById(INVOICE_SEQUENCE).map(NumberSequence::getCurrentValue).orElse(0L);
        return formatInvoiceNumber(last + 1);
    }

    public List<Map<String, Object>> findSaleLogs() {
        return jdbcLogRepository.findSaleLogs();
    }

    public Map<String, Object> findSaleLogById(Long id) {
        return jdbcLogRepository.findSaleLogById(id)
                .orElseThrow(() -> new ValidationException("saleLog.notFound", id));
    }

    public Map<String, Object> createSaleLog(SaleLogRequest request) {
        validateSaleLogRequest(request);
        return jdbcLogRepository.createSaleLog(request.getSaleId(), request.getMessage().trim());
    }

    public Map<String, Object> updateSaleLog(Long id, SaleLogRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new ValidationException("saleLog.messageRequired");
        }
        return jdbcLogRepository.updateSaleLog(id, request.getMessage().trim());
    }

    public void deleteSaleLog(Long id) {
        jdbcLogRepository.deleteSaleLog(id);
    }

    /**
     * Turns the cashier's cart into a sale. Everything happens in one transaction: the product rows and the
     * invoice counter are locked until it commits, so two tills can neither sell the same last item nor get
     * the same invoice number, and a failed checkout leaves no gap in the numbering.
     */
    @Transactional
    public ReceiptResponse checkout(Cashier cashier, CheckoutRequest request) {
        Cart cart = cartService.openCart(cashier)
                .filter(open -> !open.getItems().isEmpty())
                .orElseThrow(() -> new ValidationException("cart.empty"));
        Shift shift = shiftService.findOpenShift(cashier.getId())
                .orElseThrow(() -> new ValidationException("sale.shiftRequired"));
        List<CartItem> cartItems = List.copyOf(cart.getItems());

        Map<Long, Product> productsById = productRepository
                .findAllForUpdate(cartItems.stream().map(CartItem::getProductId).toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        cartItems.forEach(item -> validateStock(productsById.get(item.getProductId()), item));

        BigDecimal subtotal = cart.getSubtotal();
        BigDecimal finalTotal = applyPricingStrategies(cartItems, subtotal);
        List<BigDecimal> lineDiscounts = splitDiscount(cartItems, subtotal, subtotal.subtract(finalTotal));

        Sale sale = new Sale(LocalDateTime.now(), finalTotal);
        sale.setCashier(cashier);
        sale.setShift(shift);
        sale.setDiscountAmount(subtotal.subtract(finalTotal));
        applyPayments(sale, request);
        sale.setInvoiceNumber(nextInvoiceNumber());
        for (int i = 0; i < cartItems.size(); i++) {
            CartItem item = cartItems.get(i);
            Product product = productsById.get(item.getProductId());
            product.setStock(product.getStock().subtract(item.getQuantity()));
            sale.addItem(toSaleItem(item, product, lineDiscounts.get(i)));
        }

        Sale savedSale = saleRepository.save(sale);
        cartService.delete(cart);
        ReceiptResponse receipt = buildReceipt(savedSale, subtotal);
        jdbcLogRepository.logSaleAsync(savedSale.getId(),
                "Sale " + savedSale.getInvoiceNumber() + " completed by " + cashier.getFullName()
                        + " with total: " + savedSale.getTotalAmount());
        return receipt;
    }

    /**
     * Records how the customer paid. Card is always in LEK and cannot exceed the total (no change on cards);
     * foreign cash is converted with the server's buy rate; change is given in LEK cash.
     */
    private void applyPayments(Sale sale, CheckoutRequest request) {
        BigDecimal total = sale.getTotalAmount();
        List<CheckoutRequest.PaymentRequest> requested = request == null || request.payments() == null || request.payments().isEmpty()
                ? List.of(new CheckoutRequest.PaymentRequest(PaymentMethod.CASH.name(), ExchangeRateService.HOME_CURRENCY, total))
                : request.payments();
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal card = BigDecimal.ZERO;
        for (CheckoutRequest.PaymentRequest payment : requested) {
            PaymentMethod method = parseMethod(payment.method());
            String currency = ExchangeRateService.normalize(payment.currency());
            if (payment.amount() == null || payment.amount().signum() <= 0) {
                throw new ValidationException("payment.amountPositive");
            }
            if (method == PaymentMethod.CARD && !ExchangeRateService.HOME_CURRENCY.equals(currency)) {
                throw new ValidationException("payment.cardCurrency");
            }
            BigDecimal amount = payment.amount().setScale(2, RoundingMode.HALF_UP);
            BigDecimal rate = exchangeRateService.buyRate(currency);
            BigDecimal amountLek = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            sale.addPayment(new SalePayment(method, currency, amount, rate, amountLek));
            paid = paid.add(amountLek);
            if (method == PaymentMethod.CARD) {
                card = card.add(amountLek);
            }
        }
        if (card.compareTo(total) > 0) {
            throw new ValidationException("payment.cardTooMuch");
        }
        if (paid.compareTo(total) < 0) {
            throw new ValidationException("payment.insufficient", total.subtract(paid));
        }
        sale.setPaidAmount(paid);
        sale.setChangeAmount(paid.subtract(total));
    }

    private static PaymentMethod parseMethod(String method) {
        try {
            return PaymentMethod.valueOf(method == null ? "" : method.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("payment.invalidMethod");
        }
    }

    private SaleItem toSaleItem(CartItem item, Product product, BigDecimal discount) {
        BigDecimal lineTotal = item.getLineTotal().subtract(discount);
        BigDecimal taxAmount = lineTotal.subtract(CartItem.withoutTax(lineTotal, item.getTaxRate()));
        SaleItem saleItem = new SaleItem(product, item.getQuantity(), item.getPrice(), item.getUnitPriceWithoutTax(),
                item.getTaxRate(), taxAmount, item.getUnit());
        saleItem.setDiscountAmount(discount);
        saleItem.setLineTotal(lineTotal);
        saleItem.setPurchasePrice(product.getPurchasePrice());
        return saleItem;
    }

    /**
     * Spreads the sale discount over the lines in proportion to their amount, so the VAT of every line is
     * calculated on what the customer actually paid. The last line takes the rounding remainder.
     */
    static List<BigDecimal> splitDiscount(List<CartItem> items, BigDecimal subtotal, BigDecimal discount) {
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal assigned = BigDecimal.ZERO;
        for (int i = 0; i < items.size(); i++) {
            BigDecimal share;
            if (discount.signum() == 0 || subtotal.signum() == 0) {
                share = BigDecimal.ZERO.setScale(2);
            } else if (i == items.size() - 1) {
                share = discount.subtract(assigned);
            } else {
                share = discount.multiply(items.get(i).getLineTotal()).divide(subtotal, 2, RoundingMode.HALF_UP);
            }
            assigned = assigned.add(share);
            shares.add(share);
        }
        return shares;
    }

    private String nextInvoiceNumber() {
        NumberSequence sequence = numberSequenceRepository.findForUpdate(INVOICE_SEQUENCE)
                .orElseThrow(() -> new IllegalStateException("Invoice number sequence is missing"));
        return formatInvoiceNumber(sequence.next());
    }

    private static String formatInvoiceNumber(long number) {
        return String.format("%06d", number);
    }

    private BigDecimal applyPricingStrategies(List<CartItem> cartItems, BigDecimal subtotal) {
        BigDecimal total = subtotal;
        for (PricingStrategy pricingStrategy : pricingStrategies) {
            total = pricingStrategy.apply(cartItems, total);
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private void validateStock(Product product, CartItem item) {
        if (product == null || !product.isActive()) {
            throw new ValidationException("product.inactive", item.getProductName());
        }
        if (product.getStock().compareTo(item.getQuantity()) < 0) {
            throw new InsufficientStockException(product.getName());
        }
    }

    private void validateSaleLogRequest(SaleLogRequest request) {
        if (request.getSaleId() == null) {
            throw new ValidationException("saleLog.saleIdRequired");
        }
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new ValidationException("saleLog.messageRequired");
        }
    }

    private ReceiptResponse buildReceipt(Sale sale, BigDecimal subtotal) {
        List<ReceiptItemResponse> receiptItems = sale.getItems().stream()
                .map(item -> new ReceiptItemResponse(
                        item.getProduct().getName(),
                        item.getProduct().getBarcode(),
                        item.getQuantity(),
                        item.getUnit(),
                        item.getPrice(),
                        item.getPriceWithoutTax(),
                        item.getTaxRate(),
                        item.getDiscountAmount(),
                        item.getTaxAmount(),
                        item.getLineTotal()
                ))
                .toList();
        List<ReceiptResponse.VatLine> vatSummary = vatSummary(sale.getItems());

        Locale locale = LocaleContextHolder.getLocale();
        StringBuilder text = new StringBuilder();
        text.append(text("receipt.title", locale)).append("\n");
        text.append(text("receipt.invoiceNumber", locale)).append(": ").append(sale.getInvoiceNumber()).append("\n");
        text.append(text("receipt.date", locale)).append(": ").append(sale.getDate().format(RECEIPT_DATE)).append("\n");
        if (sale.getCashier() != null) {
            text.append(text("receipt.cashier", locale)).append(": ").append(sale.getCashier().getFullName()).append("\n");
        }
        text.append(RULE);
        for (ReceiptItemResponse item : receiptItems) {
            text.append(item.productName()).append("\n")
                    .append("  ").append(formatQuantity(item.quantity(), item.unit()))
                    .append(" ").append(text("unit." + item.unit(), locale))
                    .append(" x ").append(item.unitPrice())
                    .append(" = ").append(item.unitPrice().multiply(item.quantity()).setScale(2, RoundingMode.HALF_UP))
                    .append("\n");
        }
        text.append(RULE);
        text.append(text("receipt.subtotal", locale)).append(": ").append(subtotal).append("\n");
        if (sale.getDiscountAmount().signum() > 0) {
            text.append(text("receipt.discount", locale)).append(": -").append(sale.getDiscountAmount()).append("\n");
        }
        text.append(text("receipt.total", locale)).append(": ").append(sale.getTotalAmount()).append("\n");
        for (SalePayment payment : sale.getPayments()) {
            text.append(text("payment." + payment.getMethod().name().toLowerCase(), locale)).append(": ")
                    .append(payment.getAmount()).append(" ").append(payment.getCurrency());
            if (!ExchangeRateService.HOME_CURRENCY.equals(payment.getCurrency())) {
                text.append(" (x ").append(payment.getExchangeRate().stripTrailingZeros().toPlainString())
                        .append(" = ").append(payment.getAmountLek()).append(" LEK)");
            }
            text.append("\n");
        }
        if (sale.getChangeAmount().signum() > 0) {
            text.append(text("receipt.change", locale)).append(": ").append(sale.getChangeAmount()).append(" LEK\n");
        }
        text.append(RULE);
        text.append(text("receipt.vatSummary", locale)).append("\n");
        for (ReceiptResponse.VatLine vat : vatSummary) {
            text.append(text("receipt.tax", locale)).append(" ").append(vat.taxRate().stripTrailingZeros().toPlainString())
                    .append("%: ").append(text("receipt.vatBase", locale)).append(" ").append(vat.netAmount())
                    .append(", ").append(text("receipt.tax", locale)).append(" ").append(vat.taxAmount()).append("\n");
        }
        text.append(RULE);
        text.append(text("receipt.thanks", locale)).append("\n");

        return new ReceiptResponse(sale.getId(), sale.getInvoiceNumber(), sale.getDate(), subtotal,
                sale.getDiscountAmount(), sale.getTotalAmount(), sale.getPaidAmount(), sale.getChangeAmount(),
                receiptItems, vatSummary, List.copyOf(sale.getPayments()), text.toString());
    }

    private static List<ReceiptResponse.VatLine> vatSummary(List<SaleItem> items) {
        Map<BigDecimal, List<SaleItem>> byRate = new TreeMap<>();
        items.forEach(item -> byRate.computeIfAbsent(item.getTaxRate().setScale(2, RoundingMode.HALF_UP), rate -> new ArrayList<>()).add(item));
        return byRate.entrySet().stream().map(entry -> {
            BigDecimal total = entry.getValue().stream().map(SaleItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal tax = entry.getValue().stream().map(SaleItem::getTaxAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new ReceiptResponse.VatLine(entry.getKey(), total.subtract(tax), tax, total);
        }).toList();
    }

    private static String formatQuantity(BigDecimal quantity, String unit) {
        return Quantities.KILOGRAMS.equals(unit)
                ? quantity.setScale(3, RoundingMode.HALF_UP).toPlainString()
                : quantity.stripTrailingZeros().toPlainString();
    }

    private String text(String code, Locale locale) {
        return messageSource.getMessage(code, null, code, locale);
    }
}
