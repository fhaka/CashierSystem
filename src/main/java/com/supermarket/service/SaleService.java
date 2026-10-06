package com.supermarket.service;

import com.supermarket.dto.ReceiptItemResponse;
import com.supermarket.dto.ReceiptResponse;
import com.supermarket.dto.SaleLogRequest;
import com.supermarket.exception.InsufficientStockException;
import com.supermarket.exception.ValidationException;
import com.supermarket.model.CartItem;
import com.supermarket.model.Cashier;
import com.supermarket.model.Product;
import com.supermarket.model.Sale;
import com.supermarket.model.SaleItem;
import com.supermarket.repository.JdbcLogRepository;
import com.supermarket.repository.ProductRepository;
import com.supermarket.repository.SaleRepository;
import com.supermarket.strategy.PricingStrategy;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SaleService {

    private static final DateTimeFormatter RECEIPT_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final CartService cartService;
    private final ProductService productService;
    private final ProductRepository productRepository;
    private final SaleRepository saleRepository;
    private final JdbcLogRepository jdbcLogRepository;
    private final ShiftService shiftService;
    private final List<PricingStrategy> pricingStrategies;
    private final MessageSource messageSource;

    public SaleService(
            CartService cartService,
            ProductService productService,
            ProductRepository productRepository,
            SaleRepository saleRepository,
            JdbcLogRepository jdbcLogRepository,
            ShiftService shiftService,
            List<PricingStrategy> pricingStrategies,
            MessageSource messageSource
    ) {
        this.cartService = cartService;
        this.productService = productService;
        this.productRepository = productRepository;
        this.saleRepository = saleRepository;
        this.jdbcLogRepository = jdbcLogRepository;
        this.shiftService = shiftService;
        this.pricingStrategies = pricingStrategies;
        this.messageSource = messageSource;
    }

    public List<Sale> findAll() {
        return saleRepository.findAllByOrderByDateDesc();
    }

    public List<Sale> findForCashier(Long cashierId) {
        return saleRepository.findByCashierIdOrderByDateDesc(cashierId);
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

    @Transactional
    public ReceiptResponse checkout(Cashier cashier) {
        List<CartItem> cartItems = cartService.getCart(cashier.getId());
        if (cartItems.isEmpty()) {
            throw new ValidationException("cart.empty");
        }

        Map<Long, Product> productsById = cartItems.stream()
                .map(item -> productService.findById(item.getProductId()))
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        cartItems.forEach(item -> validateStock(productsById.get(item.getProductId()), item));

        BigDecimal subtotal = cartService.calculateSubtotal(cashier.getId());
        BigDecimal finalTotal = applyPricingStrategies(cartItems, subtotal);

        Sale sale = new Sale(LocalDateTime.now(), finalTotal);
        shiftService.findOpenShift(cashier.getId())
                .orElseThrow(() -> new ValidationException("sale.shiftRequired"));
        sale.setCashier(cashier);
        cartItems.forEach(item -> {
            Product product = productsById.get(item.getProductId());
            product.setStock(product.getStock() - item.getQuantity());
            sale.addItem(new SaleItem(product, item.getQuantity(), item.getPrice(), item.getUnitPriceWithoutTax(), item.getTaxRate(), item.getTaxAmount(), item.getUnit()));
        });

        productRepository.saveAll(productsById.values());
        Sale savedSale = saleRepository.save(sale);
        ReceiptResponse receipt = buildReceipt(savedSale, cartItems, subtotal);
        cartService.clear(cashier.getId());
        String cashierName = savedSale.getCashier() == null ? "Unknown cashier" : savedSale.getCashier().getFullName();
        jdbcLogRepository.logSaleAsync(savedSale.getId(), "Sale completed by " + cashierName + " with total: " + savedSale.getTotalAmount());
        return receipt;
    }

    private BigDecimal applyPricingStrategies(List<CartItem> cartItems, BigDecimal subtotal) {
        BigDecimal total = subtotal;
        for (PricingStrategy pricingStrategy : pricingStrategies) {
            total = pricingStrategy.apply(cartItems, total);
        }
        return total;
    }

    private void validateStock(Product product, CartItem item) {
        if (product.getStock() < item.getQuantity()) {
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

    private ReceiptResponse buildReceipt(Sale sale, List<CartItem> cartItems, BigDecimal subtotal) {
        List<ReceiptItemResponse> receiptItems = cartItems.stream()
                .map(item -> new ReceiptItemResponse(
                        item.getProductName(),
                        item.getBarcode(),
                        item.getQuantity(),
                        item.getPrice(),
                        item.getUnitPriceWithoutTax(),
                        item.getTaxRate(),
                        item.getTaxAmount(),
                        item.getLineTotal(),
                        item.getUnit()
                ))
                .toList();

        Locale locale = LocaleContextHolder.getLocale();
        StringBuilder printable = new StringBuilder();
        printable.append(text("receipt.title", locale)).append("\n");
        printable.append(text("receipt.saleNumber", locale)).append(": ").append(sale.getId()).append("\n");
        printable.append(text("receipt.date", locale)).append(": ").append(sale.getDate().format(RECEIPT_DATE)).append("\n");
        printable.append("------------------------------\n");
        receiptItems.forEach(item -> printable
                .append(item.getProductName())
                .append(" x")
                .append(item.getQuantity())
                .append(" ")
                .append(text("unit." + item.getUnit(), locale))
                .append(" @ ")
                .append(item.getUnitPrice())
                .append(" (")
                .append(text("receipt.tax", locale))
                .append(" ")
                .append(item.getTaxRate().stripTrailingZeros().toPlainString())
                .append("%: ")
                .append(item.getTaxAmount())
                .append(")")
                .append(" = ")
                .append(item.getLineTotal())
                .append("\n"));
        BigDecimal discountAmount = subtotal.subtract(sale.getTotalAmount());
        printable.append("------------------------------\n");
        printable.append(text("receipt.subtotal", locale)).append(": ").append(subtotal).append("\n");
        if (discountAmount.signum() > 0) {
            printable.append(text("receipt.discount", locale)).append(": -").append(discountAmount).append("\n");
        }
        printable.append(text("receipt.total", locale)).append(": ").append(sale.getTotalAmount()).append("\n");
        printable.append(text("receipt.thanks", locale)).append("\n");

        return new ReceiptResponse(
                sale.getId(),
                sale.getDate(),
                subtotal,
                sale.getTotalAmount(),
                receiptItems,
                printable.toString()
        );
    }

    private String text(String code, Locale locale) {
        return messageSource.getMessage(code, null, code, locale);
    }
}
