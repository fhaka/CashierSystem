package com.supermarket;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SupplierReturnsTest extends IntegrationTest {

    private BigDecimal stock(long productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", BigDecimal.class, productId);
    }

    private static Map<String, Object> line(long productId, String quantity) {
        return new HashMap<>(Map.of("productId", productId, "quantity", quantity));
    }

    /** 10 on the shelf at 60, then 10 bought from Lufra at 72 (with 20% VAT). */
    private long[] setUp(String admin) throws Exception {
        long p = data(postJson("/products", admin, Map.of("name", "Kos Lufra", "barcode", "991000000001", "price", "100.00",
                "purchasePrice", "60.00", "taxRate", "20", "unit", "pcs", "categoryId", 7))).path("id").asLong();
        giveStock(p, 10);
        postJson("/purchases", admin, Map.of("invoiceNumber", "F-300", "company", "Lufra", "invoiceDate", LocalDate.now().toString(),
                "items", List.of(Map.of("productId", p, "quantity", 10, "purchasePrice", "72.00", "sellingPrice", "100.00",
                        "taxRate", "20", "unit", "pcs")))).andExpect(status().isOk());
        long supplier = jdbc.queryForObject("SELECT id FROM suppliers WHERE name = 'Lufra'", Long.class);
        return new long[] {p, supplier};
    }

    @Test
    void aCreditReturnLowersStockAndWhatIsOwed() throws Exception {
        String admin = registerSuperAdmin();
        long[] ids = setUp(admin);
        long p = ids[0];
        long supplier = ids[1];

        // No price given: the last price from this supplier (72), not the average cost (66).
        JsonNode r = data(postJson("/supplier-returns", admin, Map.of("supplierId", supplier, "kind", "CREDIT", "reason", "DAMAGED",
                "lines", List.of(line(p, "2")))).andExpect(status().isOk()));
        assertThat(r.path("number").asText()).isEqualTo("K000001");
        assertThat(r.path("status").asText()).isEqualTo("DONE");
        assertThat(r.path("total").decimalValue()).isEqualByComparingTo("144");
        assertThat(stock(p)).isEqualByComparingTo("18");

        JsonNode s = data(getJson("/suppliers/" + supplier, admin));
        assertThat(s.path("supplier").path("totalCredited").decimalValue()).isEqualByComparingTo("144");
        assertThat(s.path("supplier").path("balance").decimalValue()).isEqualByComparingTo("576");      // 720 - 144
        assertThat(s.path("returns").get(0).path("number").asText()).isEqualTo("K000001");

        // The Kartela shows the goods leaving; it is not a loss; the purchase book has it as a negative line.
        JsonNode moves = data(getJson("/warehouse/products/" + p + "/movements", admin));
        assertThat(moves.get(0).path("reference").asText()).isEqualTo("SUPPLIER_RETURN");
        assertThat(moves.get(0).path("change").decimalValue()).isEqualByComparingTo("-2");
        JsonNode m = data(getJson("/accounting", admin));
        assertThat(m.path("profitAndLoss").path("current").path("stockLosses")).isEmpty();
        JsonNode book = m.path("purchases");
        assertThat(book.path("invoices")).hasSize(2);
        assertThat(book.path("invoices").get(1).path("kind").asText()).isEqualTo("CREDIT");
        assertThat(book.path("invoices").get(1).path("total").decimalValue()).isEqualByComparingTo("-144");
        assertThat(book.path("total").decimalValue()).isEqualByComparingTo("576");
        assertThat(book.path("vat").decimalValue()).isEqualByComparingTo("96");                          // 120 - 24

        // The journal lists the return once, not also as a stock adjustment.
        JsonNode journal = data(getJson("/journal", admin));
        assertThat(journal.path("entries").findValuesAsText("type")).contains("SUPPLIER_RETURN").doesNotContain("ADJUSTMENT");
    }

    @Test
    void aReplacementReturnWaitsForTheGoodsToComeBack() throws Exception {
        String admin = registerSuperAdmin();
        long[] ids = setUp(admin);
        long p = ids[0];
        long supplier = ids[1];

        Map<String, Object> l = line(p, "3");
        l.put("price", "70");
        long id = data(postJson("/supplier-returns", admin, Map.of("supplierId", supplier, "kind", "REPLACEMENT", "reason", "EXPIRED",
                "note", "Skadon nesër", "lines", List.of(l)))).path("id").asLong();
        assertThat(stock(p)).isEqualByComparingTo("17");
        assertThat(data(getJson("/supplier-returns?pending=true", admin))).hasSize(1);
        // No money moves: the debt to the supplier stays 720, and the purchase book has only the invoice.
        assertThat(data(getJson("/suppliers/" + supplier, admin)).path("supplier").path("balance").decimalValue()).isEqualByComparingTo("720");
        assertThat(data(getJson("/accounting", admin)).path("purchases").path("invoices")).hasSize(1);

        postJson("/supplier-returns/" + id + "/replaced", admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REPLACED")).andExpect(jsonPath("$.data.replacedBy").value("Admin Test"));
        assertThat(stock(p)).isEqualByComparingTo("20");
        assertThat(data(getJson("/supplier-returns?pending=true", admin))).isEmpty();
        postJson("/supplier-returns/" + id + "/replaced", admin, null).andExpect(jsonPath("$.code").value("return.notPending"));
    }

    @Test
    void returnsAreChecked() throws Exception {
        String admin = registerSuperAdmin();
        long[] ids = setUp(admin);
        long p = ids[0];
        long supplier = ids[1];

        postJson("/supplier-returns", admin, Map.of("supplierId", supplier, "kind", "CREDIT", "reason", "OTHER", "lines", List.of(line(p, "1"))))
                .andExpect(jsonPath("$.code").value("stock.noteRequired"));
        postJson("/supplier-returns", admin, Map.of("supplierId", supplier, "kind", "GIFT", "reason", "DAMAGED", "lines", List.of(line(p, "1"))))
                .andExpect(jsonPath("$.code").value("return.invalidKind"));
        postJson("/supplier-returns", admin, Map.of("supplierId", supplier, "kind", "CREDIT", "reason", "DAMAGED", "lines", List.of(line(p, "50"))))
                .andExpect(jsonPath("$.code").value("stock.wouldBeNegative"));
        postJson("/supplier-returns", admin, Map.of("kind", "CREDIT", "reason", "DAMAGED", "lines", List.of(line(p, "1"))))
                .andExpect(jsonPath("$.code").value("return.supplierRequired"));
        postJson("/supplier-returns", admin, Map.of("supplierId", supplier, "kind", "CREDIT", "reason", "DAMAGED", "lines", List.of()))
                .andExpect(jsonPath("$.code").value("return.linesRequired"));
        // Nothing was taken out by the refused returns, and the number was not used up.
        assertThat(stock(p)).isEqualByComparingTo("20");
        postJson("/inventory/adjustments", admin, Map.of("productId", p, "quantityChange", "-1", "reason", "SUPPLIER_RETURN"))
                .andExpect(jsonPath("$.code").value("stock.countReasonReserved"));

        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        getJson("/supplier-returns", cashier).andExpect(status().isForbidden());
        postJson("/supplier-returns", cashier, Map.of("supplierId", supplier, "kind", "CREDIT", "reason", "DAMAGED", "lines", List.of(line(p, "1"))))
                .andExpect(status().isForbidden());
        assertThat(data(postJson("/supplier-returns", admin, Map.of("supplierId", supplier, "kind", "CREDIT", "reason", "WRONG",
                "lines", List.of(line(p, "1"))))).path("number").asText()).isEqualTo("K000001");
    }
}
