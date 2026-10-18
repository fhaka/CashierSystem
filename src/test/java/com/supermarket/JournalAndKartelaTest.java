package com.supermarket;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JournalAndKartelaTest extends IntegrationTest {

    private static Map<String, String> totals(JsonNode page) {
        Map<String, String> totals = new HashMap<>();
        page.path("totals").forEach(t -> totals.put(t.path("type").asText(), t.path("count").asText() + "/" + t.path("amount").decimalValue().stripTrailingZeros().toPlainString()));
        return totals;
    }

    /** One of every kind of operation, all today. */
    private long[] aDayInTheShop(String admin) throws Exception {
        long milk = createProduct(admin, "970000000001", "100.00", 20);
        long bread = createProduct(admin, "970000000002", "50.00", 20);
        long customer = data(postJson("/customers", admin, Map.of("fullName", "Klient Besnik", "phone", "069", "creditLimit", "1000"))).path("id").asLong();
        long shift = openShift(admin, "500");

        putJson("/sales/cart/customer", admin, Map.of("customerId", String.valueOf(customer))).andExpect(status().isOk());
        postJson("/sales/cart", admin, Map.of("productId", milk, "quantity", 3)).andExpect(status().isOk());
        postJson("/sales/checkout", admin, Map.of("payments", List.of(Map.of("method", "CREDIT", "currency", "LEK", "amount", "300"))))
                .andExpect(status().isOk());
        postJson("/sales/cart", admin, Map.of("productId", bread, "quantity", 2)).andExpect(status().isOk());
        postJson("/sales/checkout", admin, null).andExpect(status().isOk());

        long saleId = jdbc.queryForObject("SELECT MIN(id) FROM sales", Long.class);
        long line = jdbc.queryForObject("SELECT id FROM sale_items WHERE sale_id = ?", Long.class, saleId);
        postJson("/sales/" + saleId + "/refunds", admin, Map.of("method", "CASH", "reason", "E prishur",
                "lines", List.of(Map.of("saleItemId", line, "quantity", 1)))).andExpect(status().isOk());
        postJson("/purchases", admin, Map.of("invoiceNumber", "F-77", "company", "Bulmeti", "invoiceDate", LocalDate.now().toString(),
                "items", List.of(Map.of("productId", milk, "quantity", 10, "purchasePrice", "60.00", "sellingPrice", "110.00",
                        "taxRate", "20", "unit", "pcs")))).andExpect(status().isOk());
        postJson("/inventory/adjustments", admin, Map.of("productId", bread, "quantityChange", "-1", "reason", "DAMAGED", "note", "E rënë"))
                .andExpect(status().isOk());
        long count = data(postJson("/inventory/counts", admin, Map.of("note", "Inventar"))).path("id").asLong();
        putJson("/inventory/counts/" + count + "/lines", admin, Map.of("productId", bread, "countedQuantity", "15")).andExpect(status().isOk());
        postJson("/inventory/counts/" + count + "/apply", admin, null).andExpect(status().isOk());
        postJson("/shifts/" + shift + "/cash-movements", admin, Map.of("type", "IN", "amount", "100", "reason", "Monedha")).andExpect(status().isOk());
        postJson("/shifts/" + shift + "/cash-movements", admin, Map.of("type", "OUT", "amount", "40", "reason", "Kafe")).andExpect(status().isOk());
        long supplier = jdbc.queryForObject("SELECT id FROM suppliers", Long.class);
        postJson("/suppliers/" + supplier + "/payments", admin, Map.of("amount", "200", "method", "BANK")).andExpect(status().isOk());
        postJson("/customers/" + customer + "/payments", admin, Map.of("amount", "100", "method", "CASH")).andExpect(status().isOk());
        postJson("/shifts/" + shift + "/close", admin, Map.of("closingCash", "760")).andExpect(status().isOk());
        return new long[] {milk, bread};
    }

    @Test
    void theJournalListsEveryKindOfOperationWithTotals() throws Exception {
        String admin = registerSuperAdmin();
        long[] products = aDayInTheShop(admin);

        JsonNode page = data(getJson("/journal", admin).andExpect(status().isOk()));
        Map<String, String> totals = totals(page);
        assertThat(totals).containsEntry("SALE", "2/400").containsEntry("REFUND", "1/-100")
                .containsEntry("PURCHASE", "1/-600").containsEntry("CASH_IN", "1/100").containsEntry("CASH_OUT", "1/-40")
                .containsEntry("SUPPLIER_PAYMENT", "1/-200").containsEntry("DEBT_PAYMENT", "1/100")
                .containsKeys("ADJUSTMENT", "COUNT", "SHIFT_OPEN", "SHIFT_CLOSE", "PRICE_CHANGE");
        // The adjustment made by the stock count shows once, as the count: 17 bread left, 15 counted, 2 x 50 at cost.
        assertThat(totals.get("ADJUSTMENT")).isEqualTo("1/-50");
        assertThat(totals.get("COUNT")).isEqualTo("1/-100");
        // Newest first; the shift was closed last.
        assertThat(page.path("entries").get(0).path("type").asText()).isEqualTo("SHIFT_CLOSE");
        JsonNode today = page.path("days").get(0);
        assertThat(today.path("day").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(today.path("moneyIn").decimalValue()).isEqualByComparingTo("600.00");   // sales 400 + cash in 100 + debt 100
        assertThat(today.path("moneyOut").decimalValue()).isEqualByComparingTo("940.00");  // refund 100 + purchase 600 + 40 + 200

        // Filters: kind, product, text, paging.
        assertThat(data(getJson("/journal?types=SALE,REFUND", admin)).path("total").asInt()).isEqualTo(3);
        JsonNode milkOnly = data(getJson("/journal?productId=" + products[0], admin));
        assertThat(milkOnly.path("entries").findValuesAsText("type")).containsOnly("SALE", "REFUND", "PURCHASE", "PRICE_CHANGE");
        assertThat(data(getJson("/journal?q=besnik", admin)).path("entries").findValuesAsText("type")).contains("SALE", "DEBT_PAYMENT");
        assertThat(data(getJson("/journal?size=2&page=1", admin)).path("entries")).hasSize(2);

        getJson("/journal?from=2026-01-10&to=2026-01-01", admin).andExpect(jsonPath("$.code").value("journal.invalidPeriod"));
        getJson("/journal?from=2024-01-01&to=2026-01-01", admin).andExpect(jsonPath("$.code").value("journal.periodTooLong"));
        getJson("/journal?types=SOMETHING", admin).andExpect(jsonPath("$.code").value("journal.invalidType"));
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        getJson("/journal", cashier).andExpect(status().isForbidden());

        byte[] xlsx = getJson("/journal/export.xlsx?types=SALE", admin).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(2);
            assertThat(book.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).isEqualTo("Shitje");
        }
    }

    @Test
    void theKartelaAnalysisCountsSalesProfitStockAndCustomers() throws Exception {
        String admin = registerSuperAdmin();
        long[] products = aDayInTheShop(admin);
        long milk = products[0];

        JsonNode a = data(getJson("/warehouse/products/" + milk + "/analysis?days=30", admin).andExpect(status().isOk()));
        assertThat(a.path("days").asInt()).isEqualTo(30);
        assertThat(a.path("daily")).hasSize(30);
        assertThat(a.path("quantity").decimalValue()).isEqualByComparingTo("3");
        assertThat(a.path("revenue").decimalValue()).isEqualByComparingTo("300.00");
        // 3 x (100 - VAT) = 250 without VAT; cost 3 x 50 / 1.2 = 125: profit 125, margin 50%.
        assertThat(a.path("profit").decimalValue()).isEqualByComparingTo("125.00");
        assertThat(a.path("marginPercent").decimalValue()).isEqualByComparingTo("50.0");
        assertThat(a.path("receipts").asInt()).isEqualTo(1);
        assertThat(a.path("topCustomers").get(0).path("name").asText()).isEqualTo("Klient Besnik");
        assertThat(a.path("lastPurchase").path("invoice").asText()).isEqualTo("F-77");
        // Stock: 20 - 3 sold + 1 back + 10 bought = 28 at the end of today.
        JsonNode stock = a.path("stock");
        assertThat(stock.get(stock.size() - 1).path("stock").decimalValue()).isEqualByComparingTo("28");
        assertThat(stock.get(0).path("stock").decimalValue()).isEqualByComparingTo("20");
        int weekday = LocalDate.now().getDayOfWeek().getValue() - 1;
        assertThat(a.path("byWeekday").get(weekday).decimalValue()).isEqualByComparingTo("3");

        JsonNode moves = data(getJson("/warehouse/products/" + milk + "/movements", admin));
        assertThat(moves.findValuesAsText("type")).containsExactlyInAnyOrder("SALE", "REFUND", "PURCHASE");
        assertThat(data(getJson("/warehouse/products/" + milk + "/movements?type=SALE", admin))).hasSize(1);
        assertThat(data(getJson("/warehouse/products/" + milk + "/movements?from=2020-01-01&to=2020-01-31", admin))).isEmpty();
        byte[] xlsx = getJson("/warehouse/products/" + milk + "/movements.xlsx", admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(3);
        }
    }
}
