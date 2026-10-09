package com.supermarket.service;

import com.supermarket.dto.CartSummary;
import com.supermarket.dto.CheckoutRequest;
import com.supermarket.dto.ReceiptItemResponse;
import com.supermarket.dto.ReceiptResponse;
import com.supermarket.dto.SalesPage;
import com.supermarket.dto.ShopSettings;
import com.supermarket.dto.SaleLogRequest;
import com.supermarket.exception.InsufficientStockException;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.Cart;
import com.supermarket.model.CartItem;
import com.supermarket.model.Cashier;
import com.supermarket.model.Customer;
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
import com.supermarket.util.Quantities;
import com.supermarket.util.ReceiptLayout;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SaleService {

    private static final DateTimeFormatter RECEIPT_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final String INVOICE_SEQUENCE = "SALE_INVOICE";

    private final CartService cartService;
    private final ProductRepository productRepository;
    private final SaleRepository saleRepository;
    private final NumberSequenceRepository numberSequenceRepository;
    private final JdbcLogRepository jdbcLogRepository;
    private final ShiftService shiftService;
    private final PricingService pricingService;
    private final CustomerService customerService;
    private final MessageSource messageSource;
    private final ExchangeRateService exchangeRateService;
    private final ShopSettingsService shopSettingsService;
    private final AuditService auditService;

    public SaleService(
            CartService cartService,
            ProductRepository productRepository,
            SaleRepository saleRepository,
            NumberSequenceRepository numberSequenceRepository,
            JdbcLogRepository jdbcLogRepository,
            ShiftService shiftService,
            PricingService pricingService,
            CustomerService customerService,
            MessageSource messageSource,
            ExchangeRateService exchangeRateService,
            ShopSettingsService shopSettingsService,
            AuditService auditService
    ) {
        this.cartService = cartService;
        this.productRepository = productRepository;
        this.saleRepository = saleRepository;
        this.numberSequenceRepository = numberSequenceRepository;
        this.jdbcLogRepository = jdbcLogRepository;
        this.shiftService = shiftService;
        this.pricingService = pricingService;
        this.customerService = customerService;
        this.messageSource = messageSource;
        this.exchangeRateService = exchangeRateService;
        this.shopSettingsService = shopSettingsService;
        this.auditService = auditService;
    }

    /**
     * One page of the sales log. Cashiers only ever see their own sales (cashierId is forced by the caller).
     * Dates are whole days; by default the last 30 days.
     */
    @Transactional(readOnly = true)
    public SalesPage search(LocalDate fromDay, LocalDate toDay, Long cashierId, String cashierName, String invoice, int page, int size) {
        LocalDate to = toDay == null ? LocalDate.now() : toDay;
        LocalDate from = fromDay == null ? to.minusDays(29) : fromDay;
        String name = cashierName == null || cashierName.isBlank() ? null : cashierName.trim();
        String number = invoice == null || invoice.isBlank() ? null : invoice.trim();
        int pageSize = Math.min(Math.max(size, 1), 200);
        Page<Sale> result = saleRepository.search(from.atStartOfDay(), to.atTime(LocalTime.MAX), cashierId, name, number,
                PageRequest.of(Math.max(page, 0), pageSize));
        BigDecimal total = saleRepository.sumForSearch(from.atStartOfDay(), to.atTime(LocalTime.MAX), cashierId, name, number);
        return new SalesPage(result.getContent().stream().map(SalesPage.Row::of).toList(), result.getNumber(), pageSize,
                result.getTotalElements(), total);
    }

    @Transactional(readOnly = true)
    public Sale findById(Long id) {
        Sale sale = saleRepository.findById(id).orElseThrow(() -> new ValidationException("refund.saleNotFound", id));
        sale.getItems().forEach(item -> item.getProduct().getName());
        return sale;
    }

    /** Today's numbers for the home screen (cashiers: their own sales). */
    @Transactional(readOnly = true)
    public SalesPage.Dashboard dashboard(Long cashierId, long activeProducts, long lowStock) {
        Object[] today = saleRepository.todaySummary(LocalDate.now().atStartOfDay(), cashierId).get(0);
        return new SalesPage.Dashboard((Long) today[0], (BigDecimal) today[1], activeProducts, lowStock, (LocalDateTime) today[2]);
    }

    /** Total of the cart on screen, with the same discounts as checkout, for the screen and the payment window. */
    @Transactional(readOnly = true)
    public CartSummary cartSummary(Cashier cashier) {
        Optional<Cart> cart = cartService.openCart(cashier);
        List<CartItem> items = cart.map(c -> List.copyOf(c.getItems())).orElse(List.of());
        PricingService.CartPrice price = pricingService.price(items, cart.map(Cart::getManualDiscountPercent).orElse(null),
                LocalDateTime.now());
        List<CartSummary.LinePromotion> promotions = price.lines().stream()
                .filter(line -> line.promotion() != null)
                .map(line -> new CartSummary.LinePromotion(line.item().getProductId(), line.item().getProductName(),
                        line.promotion().getName(), line.promotionDiscount()))
                .toList();
        Customer customer = cart.map(Cart::getCustomer).orElse(null);
        return new CartSummary(price.subtotal(), price.promotionDiscount(), price.manualDiscountPercent(), price.manualDiscount(),
                price.otherDiscount(), price.discount(), price.total(), promotions, customer,
                customer == null ? null : customerService.pointValue().multiply(BigDecimal.valueOf(customer.getPoints())));
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

        // Lock first, then price: prices, promotions and stock are all read under the lock.
        Map<Long, Product> productsById = productRepository
                .findAllForUpdate(cartItems.stream().map(CartItem::getProductId).toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        // Single pieces and boxes of the same product are separate lines: the stock must cover all of them.
        Map<Long, BigDecimal> piecesByProduct = new LinkedHashMap<>();
        cartItems.forEach(item -> piecesByProduct.merge(item.getProductId(), item.getStockQuantity(), BigDecimal::add));
        cartItems.forEach(item -> validateStock(productsById.get(item.getProductId()), item, piecesByProduct.get(item.getProductId())));

        LocalDateTime now = LocalDateTime.now();
        PricingService.CartPrice price = pricingService.price(cartItems, cart.getManualDiscountPercent(), now);
        Customer customer = cart.getCustomer();

        Sale sale = new Sale(now, price.total());
        sale.setCashier(cashier);
        sale.setShift(shift);
        sale.setCustomer(customer);
        sale.setDiscountAmount(price.discount());
        PaidWith paidWith = applyPayments(sale, request, customer);
        sale.setInvoiceNumber(nextInvoiceNumber());
        for (PricingService.LinePrice line : price.lines()) {
            Product product = productsById.get(line.item().getProductId());
            product.setStock(product.getStock().subtract(line.item().getStockQuantity()));
            sale.addItem(toSaleItem(line, product));
        }
        if (customer != null) {
            sale.setPointsEarned(customerService.pointsEarnedFor(price.total().subtract(paidWith.pointsLek())));
        }

        Sale savedSale = saleRepository.save(sale);
        if (customer != null) {
            customerService.recordSale(customer, cashier, savedSale, savedSale.getPointsEarned(), paidWith.points(), paidWith.credit());
        }
        cartService.delete(cart);
        ReceiptResponse receipt = buildReceipt(savedSale, price);
        jdbcLogRepository.logSaleAsync(savedSale.getId(),
                "Sale " + savedSale.getInvoiceNumber() + " completed by " + cashier.getFullName()
                        + " with total: " + savedSale.getTotalAmount());
        return receipt;
    }

    /** Points used (and their LEK value) and the amount charged to the customer's account. */
    private record PaidWith(int points, BigDecimal pointsLek, BigDecimal credit) {
    }

    /**
     * Records how the customer paid. Card, credit and points are in LEK and together cannot exceed the total
     * (no change is given on them); foreign cash is converted with the server's buy rate; change is LEK cash.
     * Credit needs a customer with enough credit left; points need a customer with enough points.
     */
    private PaidWith applyPayments(Sale sale, CheckoutRequest request, Customer customer) {
        BigDecimal total = sale.getTotalAmount();
        List<CheckoutRequest.PaymentRequest> requested = request == null || request.payments() == null || request.payments().isEmpty()
                ? List.of(new CheckoutRequest.PaymentRequest(PaymentMethod.CASH.name(), ExchangeRateService.HOME_CURRENCY, total))
                : request.payments();
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal card = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        BigDecimal pointsLek = BigDecimal.ZERO;
        for (CheckoutRequest.PaymentRequest payment : requested) {
            PaymentMethod method = parseMethod(payment.method());
            String currency = ExchangeRateService.normalize(payment.currency());
            if (payment.amount() == null || payment.amount().signum() <= 0) {
                throw new ValidationException("payment.amountPositive");
            }
            if (method != PaymentMethod.CASH && !ExchangeRateService.HOME_CURRENCY.equals(currency)) {
                throw new ValidationException("payment.cardCurrency");
            }
            BigDecimal amount = payment.amount().setScale(2, RoundingMode.HALF_UP);
            BigDecimal rate = exchangeRateService.buyRate(currency);
            BigDecimal amountLek = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            sale.addPayment(new SalePayment(method, currency, amount, rate, amountLek));
            paid = paid.add(amountLek);
            switch (method) {
                case CARD -> card = card.add(amountLek);
                case CREDIT -> credit = credit.add(amountLek);
                case POINTS -> pointsLek = pointsLek.add(amountLek);
                default -> { }
            }
        }
        if (card.compareTo(total) > 0) {
            throw new ValidationException("payment.cardTooMuch");
        }
        if (card.add(credit).add(pointsLek).compareTo(total) > 0) {
            throw new ValidationException("payment.nonCashTooMuch");
        }
        if (paid.compareTo(total) < 0) {
            throw new ValidationException("payment.insufficient", total.subtract(paid));
        }
        int points = 0;
        if (pointsLek.signum() > 0) {
            if (customer == null) {
                throw new ValidationException("payment.customerRequired");
            }
            BigDecimal pointsNeeded = pointsLek.divide(customerService.pointValue(), 6, RoundingMode.HALF_UP);
            if (pointsNeeded.stripTrailingZeros().scale() > 0) {
                throw new ValidationException("payment.pointsWhole", customerService.pointValue().stripTrailingZeros().toPlainString());
            }
            points = pointsNeeded.intValueExact();
            if (points > customer.getPoints()) {
                throw new ValidationException("payment.notEnoughPoints", customer.getPoints());
            }
        }
        if (credit.signum() > 0) {
            if (customer == null) {
                throw new ValidationException("payment.customerRequired");
            }
            if (customer.getCreditAvailable() == null) {
                throw new ValidationException("payment.creditNotAllowed", customer.getFullName());
            }
            if (credit.compareTo(customer.getCreditAvailable()) > 0) {
                throw new ValidationException("payment.creditLimit", customer.getCreditAvailable());
            }
        }
        sale.setPaidAmount(paid);
        sale.setChangeAmount(paid.subtract(total));
        return new PaidWith(points, pointsLek, credit);
    }

    private static PaymentMethod parseMethod(String method) {
        try {
            return PaymentMethod.valueOf(method == null ? "" : method.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ValidationException("payment.invalidMethod");
        }
    }

    private SaleItem toSaleItem(PricingService.LinePrice line, Product product) {
        CartItem item = line.item();
        BigDecimal discount = line.discount();
        BigDecimal lineTotal = line.total();
        BigDecimal taxAmount = lineTotal.subtract(CartItem.withoutTax(lineTotal, item.getTaxRate()));
        SaleItem saleItem;
        if (item.getPackageId() == null) {
            saleItem = new SaleItem(product, item.getQuantity(), item.getPrice(), item.getUnitPriceWithoutTax(),
                    item.getTaxRate(), taxAmount, item.getUnit());
        } else {
            // Boxes: the sale line counts pieces, at the box price shared over its pieces; the boxes are kept too.
            BigDecimal pieces = BigDecimal.valueOf(item.getPiecesPerUnit());
            BigDecimal piecePrice = item.getPrice().divide(pieces, 2, RoundingMode.HALF_UP);
            saleItem = new SaleItem(product, item.getStockQuantity(), piecePrice, CartItem.withoutTax(piecePrice, item.getTaxRate()),
                    item.getTaxRate(), taxAmount, item.getUnit());
            saleItem.setPackage(item.getPackageId(), item.getPackageName(), item.getQuantity(), item.getPrice());
        }
        saleItem.setDiscountAmount(discount);
        saleItem.setLineTotal(lineTotal);
        saleItem.setPurchasePrice(product.getPurchasePrice());
        saleItem.setPromotion(line.promotion());
        saleItem.setPromotionDiscount(line.promotionDiscount());
        return saleItem;
    }

    private String nextInvoiceNumber() {
        NumberSequence sequence = numberSequenceRepository.findForUpdate(INVOICE_SEQUENCE)
                .orElseThrow(() -> new IllegalStateException("Invoice number sequence is missing"));
        return formatInvoiceNumber(sequence.next());
    }

    private static String formatInvoiceNumber(long number) {
        return String.format("%06d", number);
    }

    private void validateStock(Product product, CartItem item, BigDecimal piecesInCart) {
        if (product == null || !product.isActive()) {
            throw new ValidationException("product.inactive", item.getProductName());
        }
        if (product.getStock().compareTo(piecesInCart) < 0) {
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

    private ReceiptResponse buildReceipt(Sale sale, PricingService.CartPrice price) {
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
        String text = receiptText(sale, new ReceiptDiscounts(price.subtotal(), price.promotionDiscount(),
                price.manualDiscountPercent(), price.manualDiscount(), price.otherDiscount()), shopSettingsService.get());
        sale.setReceiptText(text);
        Customer customer = sale.getCustomer();
        return new ReceiptResponse(sale.getId(), sale.getInvoiceNumber(), sale.getDate(), price.subtotal(),
                sale.getDiscountAmount(), sale.getTotalAmount(), sale.getPaidAmount(), sale.getChangeAmount(),
                receiptItems, vatSummary(sale.getItems()), List.copyOf(sale.getPayments()),
                customer == null ? null : customer.getFullName(), sale.getPointsEarned(),
                customer == null ? null : customer.getPoints(), customer == null ? null : customer.getBalance(), text);
    }

    /** Discounts as the receipt shows them. */
    private record ReceiptDiscounts(BigDecimal subtotal, BigDecimal promotion, BigDecimal manualPercent, BigDecimal manual,
                                    BigDecimal other) {

        /** For sales saved before receipts were kept: promotions are on the lines, the rest is one discount. */
        static ReceiptDiscounts of(Sale sale) {
            BigDecimal promotion = sale.getItems().stream().map(SaleItem::getPromotionDiscount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal other = sale.getDiscountAmount().subtract(promotion).max(BigDecimal.ZERO);
            return new ReceiptDiscounts(sale.getTotalAmount().add(sale.getDiscountAmount()), promotion, BigDecimal.ZERO,
                    BigDecimal.ZERO, other);
        }
    }

    /** The receipt as printed: shop header, lines, discounts, total, payments, customer, VAT and the footer. */
    private String receiptText(Sale sale, ReceiptDiscounts discounts, ShopSettings shop) {
        Locale locale = LocaleContextHolder.getLocale();
        ReceiptLayout r = shopSettingsService.header(shop);
        r.center(text("receipt.title", locale));
        r.row(text("receipt.invoiceNumber", locale), sale.getInvoiceNumber());
        r.row(text("receipt.date", locale), sale.getDate().format(RECEIPT_DATE));
        if (sale.getCashier() != null) {
            r.row(text("receipt.cashier", locale), sale.getCashier().getFullName());
        }
        r.rule();
        for (SaleItem item : sale.getItems()) {
            r.line(item.getProduct().getName());
            if (item.getPackageId() != null) {
                r.row("  " + item.getPackageCount().stripTrailingZeros().toPlainString() + " " + item.getPackageName()
                        + " x " + ReceiptLayout.format(item.getPackagePrice()), item.getPackagePrice().multiply(item.getPackageCount()));
            } else {
                r.row("  " + formatQuantity(item.getQuantity(), item.getUnit()) + " " + text("unit." + item.getUnit(), locale)
                        + " x " + ReceiptLayout.format(item.getPrice()), item.getPrice().multiply(item.getQuantity()));
            }
            if (item.getPromotion() != null && item.getPromotionDiscount().signum() > 0) {
                r.row("  " + text("receipt.promotion", locale) + " " + item.getPromotion().getName(),
                        "-" + ReceiptLayout.format(item.getPromotionDiscount()));
            }
        }
        r.rule();
        if (discounts.subtotal().compareTo(sale.getTotalAmount()) != 0) {
            r.row(text("receipt.subtotal", locale), discounts.subtotal());
        }
        if (discounts.promotion().signum() > 0) {
            r.row(text("receipt.promotions", locale), "-" + ReceiptLayout.format(discounts.promotion()));
        }
        if (discounts.manual().signum() > 0) {
            r.row(text("receipt.manualDiscount", locale) + " " + discounts.manualPercent().stripTrailingZeros().toPlainString() + "%",
                    "-" + ReceiptLayout.format(discounts.manual()));
        }
        if (discounts.other().signum() > 0) {
            r.row(text("receipt.discount", locale), "-" + ReceiptLayout.format(discounts.other()));
        }
        r.row(text("receipt.total", locale) + " LEK", sale.getTotalAmount());
        r.blank();
        for (SalePayment payment : sale.getPayments()) {
            String label = text("payment." + payment.getMethod().name().toLowerCase(), locale);
            if (ExchangeRateService.HOME_CURRENCY.equals(payment.getCurrency())) {
                r.row(label, payment.getAmount());
            } else {
                r.row(label + " " + ReceiptLayout.format(payment.getAmount()) + " " + payment.getCurrency()
                        + " x " + payment.getExchangeRate().stripTrailingZeros().toPlainString(), payment.getAmountLek());
            }
        }
        if (sale.getChangeAmount().signum() > 0) {
            r.row(text("receipt.change", locale), sale.getChangeAmount());
        }
        Customer customer = sale.getCustomer();
        if (customer != null) {
            r.rule();
            r.line(text("receipt.customer", locale) + ": " + customer.getFullName() + " (" + customer.getCardNumber() + ")");
            r.row(text("receipt.pointsEarned", locale), sale.getPointsEarned());
            r.row(text("receipt.pointsTotal", locale), customer.getPoints());
            if (customer.getBalance().signum() != 0) {
                r.row(text("receipt.accountBalance", locale), customer.getBalance());
            }
        }
        r.rule();
        r.line(text("receipt.vatSummary", locale));
        for (ReceiptResponse.VatLine vat : vatSummary(sale.getItems())) {
            r.row(text("receipt.tax", locale) + " " + vat.taxRate().stripTrailingZeros().toPlainString() + "% ("
                    + text("receipt.vatBase", locale) + " " + ReceiptLayout.format(vat.netAmount()) + ")", vat.taxAmount());
        }
        r.rule();
        r.center(shop.receiptFooter().isBlank() ? text("receipt.thanks", locale) : shop.receiptFooter());
        return r.toString();
    }

    /**
     * The receipt of an earlier sale, marked as a copy: exactly as it was printed, or rebuilt from the sale for
     * sales made before receipts were kept. Every reprint is written to the audit log.
     */
    @Transactional
    public String receiptCopy(Long saleId, Cashier cashier) {
        Sale sale = findById(saleId);
        ShopSettings shop = shopSettingsService.get();
        String original = sale.getReceiptText() != null ? sale.getReceiptText() : receiptText(sale, ReceiptDiscounts.of(sale), shop);
        String mark = "*** " + text("receipt.copy", LocaleContextHolder.getLocale()) + " ***";
        auditService.record(cashier, "RECEIPT_REPRINTED", "SALE", sale.getId(), sale.getInvoiceNumber());
        return new ReceiptLayout(shop.receiptWidth()).center(mark) + original + new ReceiptLayout(shop.receiptWidth()).center(mark);
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
