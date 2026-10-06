package com.supermarket.service;

import com.supermarket.dto.ReceiptItemResponse;
import com.supermarket.dto.ReceiptResponse;
import com.supermarket.dto.SaleLogRequest;
import com.supermarket.exception.InsufficientStockException;
import com.supermarket.model.CartItem;
import com.supermarket.model.Cashier;
import com.supermarket.model.Product;
import com.supermarket.model.Sale;
import com.supermarket.model.SaleItem;
import com.supermarket.repository.JdbcLogRepository;
import com.supermarket.repository.ProductRepository;
import com.supermarket.repository.SaleRepository;
import com.supermarket.strategy.PricingStrategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SaleService {

    private final CartService cartService;
    private final ProductService productService;
    private final ProductRepository productRepository;
    private final SaleRepository saleRepository;
    private final JdbcLogRepository jdbcLogRepository;
    private final ShiftService shiftService;
    private final List<PricingStrategy> pricingStrategies;

    public SaleService(
            CartService cartService,
            ProductService productService,
            ProductRepository productRepository,
            SaleRepository saleRepository,
            JdbcLogRepository jdbcLogRepository,
            ShiftService shiftService,
            List<PricingStrategy> pricingStrategies
    ) {
        this.cartService = cartService;
        this.productService = productService;
        this.productRepository = productRepository;
        this.saleRepository = saleRepository;
        this.jdbcLogRepository = jdbcLogRepository;
        this.shiftService = shiftService;
        this.pricingStrategies = pricingStrategies;
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
                .orElseThrow(() -> new IllegalArgumentException("JDBC sale log not found with id: " + id));
    }

    public Map<String, Object> createSaleLog(SaleLogRequest request) {
        validateSaleLogRequest(request);
        return jdbcLogRepository.createSaleLog(request.getSaleId(), request.getMessage().trim());
    }

    public Map<String, Object> updateSaleLog(Long id, SaleLogRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("Sale log message is required");
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
            throw new IllegalArgumentException("Cart is empty");
        }

        Map<Long, Product> productsById = cartItems.stream()
                .map(item -> productService.findById(item.getProductId()))
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        cartItems.forEach(item -> validateStock(productsById.get(item.getProductId()), item));

        BigDecimal subtotal = cartService.calculateSubtotal(cashier.getId());
        BigDecimal finalTotal = applyPricingStrategies(cartItems, subtotal);

        Sale sale = new Sale(LocalDateTime.now(), finalTotal);
        shiftService.findOpenShift(cashier.getId())
                .orElseThrow(() -> new IllegalArgumentException("Open a shift before checkout"));
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
            throw new InsufficientStockException("Not enough stock for product: " + product.getName());
        }
    }

    private void validateSaleLogRequest(SaleLogRequest request) {
        if (request.getSaleId() == null) {
            throw new IllegalArgumentException("Sale id is required");
        }
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new IllegalArgumentException("Sale log message is required");
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

        StringBuilder printable = new StringBuilder();
        printable.append("SUPERMARKET RECEIPT\n");
        printable.append("Sale ID: ").append(sale.getId()).append("\n");
        printable.append("Date: ").append(sale.getDate()).append("\n");
        printable.append("------------------------------\n");
        receiptItems.forEach(item -> printable
                .append(item.getProductName())
                .append(" x")
                .append(item.getQuantity())
                .append(" ")
                .append(item.getUnit())
                .append(" @ ")
                .append(item.getUnitPrice())
                .append(" (tax ")
                .append(item.getTaxRate())
                .append("%: ")
                .append(item.getTaxAmount())
                .append(")")
                .append(" = ")
                .append(item.getLineTotal())
                .append("\n"));
        BigDecimal discountAmount = subtotal.subtract(sale.getTotalAmount());
        printable.append("------------------------------\n");
        printable.append("Subtotal: ").append(subtotal).append("\n");
        if (discountAmount.signum() > 0) {
            printable.append("Discount: -").append(discountAmount).append("\n");
        }
        printable.append("Total: ").append(sale.getTotalAmount()).append("\n");
        printable.append("Thank you!\n");

        return new ReceiptResponse(
                sale.getId(),
                sale.getDate(),
                subtotal,
                sale.getTotalAmount(),
                receiptItems,
                printable.toString()
        );
    }
}
