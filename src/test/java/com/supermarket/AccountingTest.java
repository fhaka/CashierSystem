package com.supermarket;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountingTest extends IntegrationTest {

    private long product(String token, String barcode, String price, String cost, String vat, int stock) throws Exception {
        return data(postJson("/products", token, Map.of("name", "Artikull " + barcode, "barcode", barcode, "price", price,
                "purchasePrice", cost, "taxRate", vat, "stock", stock, "unit", "pcs", "categoryId", 7))).path("id").asLong();
    }

    private static JsonNode rate(JsonNode rates, String rate) {
        for (JsonNode r : rates) {
            if (r.path("rate").decimalValue().compareTo(new java.math.BigDecimal(rate)) == 0) {
                return r;
            }
        }
        throw new AssertionError("no rate " + rate);
    }

    /**
     * A at 120 (VAT 20%, cost 60 with VAT), 10 in stock; B at 100 (VAT 0%), bought 10 at 50. Sold 2 A + 1 B, 1 A refunded,
     * 1 A damaged, rent 1000 by bank.
     */
    @Test
    void oneMonthForTheAccountant() throws Exception {
        String admin = registerSuperAdmin();
        long a = product(admin, "980000000001", "120.00", "60.00", "20", 10);
        long b = product(admin, "980000000002", "100.00", "50.00", "0", 0);
        postJson("/purchases", admin, Map.of("invoiceNumber", "F-501", "company", "Furnitori Alfa", "invoiceDate", LocalDate.now().toString(),
                "items", List.of(Map.of("productId", b, "quantity", 10, "purchasePrice", "50.00", "sellingPrice", "100.00",
                        "taxRate", "0", "unit", "pcs")))).andExpect(status().isOk());
        jdbc.update("UPDATE suppliers SET tax_number = 'L12345678A'");
        openShift(admin, "0");
        postJson("/sales/cart", admin, Map.of("productId", a, "quantity", 2)).andExpect(status().isOk());
        postJson("/sales/cart", admin, Map.of("productId", b, "quantity", 1)).andExpect(status().isOk());
        postJson("/sales/checkout", admin, null).andExpect(status().isOk());
        long saleId = jdbc.queryForObject("SELECT id FROM sales", Long.class);
        long lineA = jdbc.queryForObject("SELECT id FROM sale_items WHERE product_id = ?", Long.class, a);
        postJson("/sales/" + saleId + "/refunds", admin, Map.of("method", "CASH", "reason", "Kthim",
                "lines", List.of(Map.of("saleItemId", lineA, "quantity", 1)))).andExpect(status().isOk());
        postJson("/inventory/adjustments", admin, Map.of("productId", a, "quantityChange", "-1", "reason", "DAMAGED")).andExpect(status().isOk());
        long rent = jdbc.queryForObject("SELECT id FROM expense_categories WHERE name = 'Qira'", Long.class);
        postJson("/expenses", admin, Map.of("categoryId", rent, "amount", "1000", "method", "BANK")).andExpect(status().isOk());

        JsonNode m = data(getJson("/accounting", admin).andExpect(status().isOk()));
        assertThat(m.path("month").asText()).isEqualTo(YearMonth.now().toString());

        // Profit and loss: 340 sold - 120 refunded = 220 with VAT, 200 without; cost 100 (2 A at 50 + 1 B at 50 - 1 A at 50).
        JsonNode pl = m.path("profitAndLoss").path("current");
        assertThat(pl.path("revenue").decimalValue()).isEqualByComparingTo("220");
        assertThat(pl.path("vat").decimalValue()).isEqualByComparingTo("20");
        assertThat(pl.path("revenueWithoutVat").decimalValue()).isEqualByComparingTo("200");
        assertThat(pl.path("costOfGoods").decimalValue()).isEqualByComparingTo("100");
        assertThat(pl.path("grossProfit").decimalValue()).isEqualByComparingTo("100");
        assertThat(pl.path("stockLosses").get(0).path("key").asText()).isEqualTo("DAMAGED");
        assertThat(pl.path("stockLossTotal").decimalValue()).isEqualByComparingTo("50");      // 1 A at 60 / 1.2
        assertThat(pl.path("expenses").get(0).path("label").asText()).isEqualTo("Qira");
        assertThat(pl.path("expenseTotal").decimalValue()).isEqualByComparingTo("1000");
        assertThat(pl.path("netProfit").decimalValue()).isEqualByComparingTo("-950");
        assertThat(pl.path("netMargin").decimalValue()).isEqualByComparingTo("-475.0");
        assertThat(m.path("profitAndLoss").path("previous").path("revenue").decimalValue()).isEqualByComparingTo("0");

        // Sales book: one day, the receipt and the refund, by rate.
        JsonNode sales = m.path("sales");
        assertThat(sales.path("rates")).hasSize(2);   // 0% and 20%
        JsonNode day = sales.path("days").get(0);
        assertThat(day.path("receipts").asInt()).isEqualTo(1);
        assertThat(day.path("refunds").asInt()).isEqualTo(1);
        assertThat(day.path("firstInvoice").asText()).isEqualTo("000001");
        assertThat(rate(day.path("rates"), "20").path("total").decimalValue()).isEqualByComparingTo("120");
        assertThat(rate(day.path("rates"), "20").path("vat").decimalValue()).isEqualByComparingTo("20");
        assertThat(rate(day.path("rates"), "0").path("base").decimalValue()).isEqualByComparingTo("100");
        assertThat(day.path("rates")).hasSize(2);
        assertThat(sales.path("total").decimalValue()).isEqualByComparingTo("220");
        assertThat(sales.path("vat").decimalValue()).isEqualByComparingTo("20");
        assertThat(sales.path("documents")).hasSize(2);
        assertThat(sales.path("documents").get(1).path("kind").asText()).isEqualTo("REFUND");
        assertThat(sales.path("documents").get(1).path("total").decimalValue()).isEqualByComparingTo("-120");

        // Purchase book: 10 B at 50 without VAT.
        JsonNode purchases = m.path("purchases");
        JsonNode invoice = purchases.path("invoices").get(0);
        assertThat(invoice.path("invoice").asText()).isEqualTo("F-501");
        assertThat(invoice.path("taxNumber").asText()).isEqualTo("L12345678A");
        assertThat(rate(invoice.path("rates"), "0").path("base").decimalValue()).isEqualByComparingTo("500");
        assertThat(purchases.path("vat").decimalValue()).isEqualByComparingTo("0");
        assertThat(purchases.path("total").decimalValue()).isEqualByComparingTo("500");

        // Stock now: A 10 - 2 + 1 - 1 = 8 at 60, B 10 - 1 = 9 at 50. Both were created this month: nothing at the start.
        JsonNode end = m.path("stockAtEnd");
        assertThat(end.path("value").decimalValue()).isEqualByComparingTo("930");          // 480 + 450
        assertThat(end.path("valueWithoutVat").decimalValue()).isEqualByComparingTo("850"); // 400 + 450
        assertThat(m.path("stockAtStart").path("products")).isEmpty();
        // A came in as opening stock (10 at 60 = 500 without VAT): not a loss, but goods in for the stock check.
        assertThat(m.path("openingStock").decimalValue()).isEqualByComparingTo("500");

        // Errors and permissions.
        getJson("/accounting?month=2026-13", admin).andExpect(jsonPath("$.code").value("accounting.invalidMonth"));
        getJson("/accounting?month=" + YearMonth.now().plusMonths(1), admin).andExpect(jsonPath("$.code").value("accounting.futureMonth"));
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        getJson("/accounting", cashier).andExpect(status().isForbidden());

        byte[] xlsx = getJson("/accounting/export.xlsx", admin).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(5);
            assertThat(book.getSheetName(0)).isEqualTo("Fitimi dhe humbja");
            Sheet salesBook = book.getSheet("Libri i shitjeve");
            assertThat(salesBook.getLastRowNum()).isEqualTo(2);    // header, one day, total
            assertThat(book.getSheet("Libri i blerjeve").getRow(1).getCell(3).getStringCellValue()).isEqualTo("L12345678A");
        }
    }

    /** The stock at the end of last month puts back what was sold since, at the cost of that moment. */
    @Test
    void theStockValueGoesBackInTime() throws Exception {
        String admin = registerSuperAdmin();
        long c = product(admin, "980000000003", "20.00", "12.00", "20", 5);
        LocalDateTime longAgo = LocalDate.now().withDayOfMonth(1).minusDays(20).atTime(9, 0);
        jdbc.update("UPDATE price_changes SET changed_at = ? WHERE product_id = ?", longAgo, c);
        jdbc.update("UPDATE stock_adjustments SET created_at = ? WHERE product_id = ?", longAgo, c);   // its opening stock
        // A delivery this month changes the cost to the average: (5 x 12 + 5 x 18) / 10 = 15.
        postJson("/purchases", admin, Map.of("invoiceNumber", "F-9", "company", "Furnitori", "invoiceDate", LocalDate.now().toString(),
                "items", List.of(Map.of("productId", c, "quantity", 5, "purchasePrice", "18.00", "sellingPrice", "20.00",
                        "taxRate", "20", "unit", "pcs")))).andExpect(status().isOk());
        openShift(admin, "0");
        postJson("/sales/cart", admin, Map.of("productId", c, "quantity", 3)).andExpect(status().isOk());
        postJson("/sales/checkout", admin, null).andExpect(status().isOk());

        JsonNode m = data(getJson("/accounting", admin));
        JsonNode start = m.path("stockAtStart");
        assertThat(start.path("at").asText()).isEqualTo(LocalDate.now().withDayOfMonth(1).minusDays(1).toString());
        assertThat(start.path("products").get(0).path("stock").decimalValue()).isEqualByComparingTo("5");   // 7 now + 3 sold - 5 bought
        assertThat(start.path("products").get(0).path("cost").decimalValue()).isEqualByComparingTo("12");
        assertThat(start.path("value").decimalValue()).isEqualByComparingTo("60");
        JsonNode end = m.path("stockAtEnd");
        assertThat(end.path("products").get(0).path("stock").decimalValue()).isEqualByComparingTo("7");
        assertThat(end.path("value").decimalValue()).isEqualByComparingTo("105");                        // 7 x 15

    }

    /** A loss keeps the cost of its day: a later delivery at another price does not change it. */
    @Test
    void aStockLossKeepsTheCostOfItsDay() throws Exception {
        String admin = registerSuperAdmin();
        long d = product(admin, "980000000004", "100.00", "60.00", "20", 10);
        postJson("/inventory/adjustments", admin, Map.of("productId", d, "quantityChange", "-2", "reason", "EXPIRED")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT unit_cost FROM stock_adjustments WHERE reason = 'EXPIRED'", java.math.BigDecimal.class))
                .isEqualByComparingTo("60");
        // 8 at 60 + 8 at 90 = cost 75 from now on.
        postJson("/purchases", admin, Map.of("invoiceNumber", "F-10", "company", "Furnitori", "invoiceDate", LocalDate.now().toString(),
                "items", List.of(Map.of("productId", d, "quantity", 8, "purchasePrice", "90.00", "sellingPrice", "100.00",
                        "taxRate", "20", "unit", "pcs")))).andExpect(status().isOk());

        JsonNode pl = data(getJson("/accounting", admin)).path("profitAndLoss").path("current");
        assertThat(pl.path("stockLosses").get(0).path("key").asText()).isEqualTo("EXPIRED");
        assertThat(pl.path("stockLossTotal").decimalValue()).isEqualByComparingTo("100");   // 2 x 60 / 1.2, not 2 x 75 / 1.2
        assertThat(pl.path("stockLosses")).hasSize(1);                                       // the opening stock is not a loss
    }
}
