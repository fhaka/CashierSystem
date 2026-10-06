package com.supermarket.dto;

import com.supermarket.model.Sale;
import com.supermarket.model.SalePayment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** One page of the sales log, with the count and total of everything that matches the filter. */
public record SalesPage(List<Row> rows, int page, int size, long totalCount, BigDecimal totalAmount) {

    /** A sale in the list; open it (GET /sales/{id}) for its lines. */
    public record Row(Long id, String invoiceNumber, LocalDateTime date, String cashierName, String customerName,
                      int lines, BigDecimal totalAmount, BigDecimal discountAmount, List<String> paymentMethods) {

        public static Row of(Sale sale) {
            return new Row(sale.getId(), sale.getInvoiceNumber(), sale.getDate(),
                    sale.getCashier() == null ? null : sale.getCashier().getFullName(),
                    sale.getCustomer() == null ? null : sale.getCustomer().getFullName(),
                    sale.getItems().size(), sale.getTotalAmount(), sale.getDiscountAmount(),
                    sale.getPayments().stream().map(SalePayment::getMethod).map(Enum::name).distinct().toList());
        }
    }

    /** Small numbers for the home screen. */
    public record Dashboard(long salesToday, BigDecimal revenueToday, long activeProducts, long lowStockProducts,
                            LocalDateTime lastSaleAt) {
    }
}
