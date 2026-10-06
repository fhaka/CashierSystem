package com.supermarket;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InventoryAndSuppliersTest extends IntegrationTest {

    private long product(String token, String barcode, String price, String stock, String unit, String minStock) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("name", "Produkt " + barcode, "barcode", barcode, "price", price,
                "purchasePrice", "1.00", "taxRate", "20", "stock", stock, "unit", unit, "categoryId", 7));
        if (minStock != null) {
            body.put("minStock", minStock);
        }
        return data(postJson("/products", token, body).andExpect(status().isOk())).path("id").asLong();
    }

    private void purchase(String token, String number, String company, LocalDate date, long productId, String quantity,
                          String cost, LocalDate expiry) throws Exception {
        Map<String, Object> item = new HashMap<>(Map.of("productId", productId, "quantity", quantity, "purchasePrice", cost,
                "sellingPrice", "100.00", "taxRate", "20", "unit", "pcs"));
        if (expiry != null) {
            item.put("expiryDate", expiry.toString());
        }
        postJson("/purchases", token, Map.of("invoiceNumber", number, "company", company, "invoiceDate", date.toString(),
                "items", List.of(item))).andExpect(status().isOk());
    }

    private BigDecimal stock(long productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", BigDecimal.class, productId);
    }

    @Test
    void suppliersAreCreatedFromPurchasesAndTrackWhatIsOwed() throws Exception {
        String admin = registerSuperAdmin();
        long p = product(admin, "700000000001", "100.00", "0", "pcs", null);
        purchase(admin, "F-1", "Bulmeti Shpk", LocalDate.now(), p, "10", "50.00", null);
        purchase(admin, "F-2", "  bulmeti shpk ", LocalDate.now(), p, "4", "50.00", null);

        JsonNode suppliers = data(getJson("/suppliers", admin).andExpect(status().isOk()));
        assertThat(suppliers).hasSize(1);
        long supplierId = suppliers.get(0).path("id").asLong();
        assertThat(suppliers.get(0).path("totalInvoiced").decimalValue()).isEqualByComparingTo("700.00");

        postJson("/suppliers/" + supplierId + "/payments", admin, Map.of("amount", "300", "method", "BANK", "note", "Transfertë"))
                .andExpect(status().isOk());
        postJson("/suppliers/" + supplierId + "/payments", admin, Map.of("amount", "-5", "method", "BANK"))
                .andExpect(status().isBadRequest());

        JsonNode detail = data(getJson("/suppliers/" + supplierId, admin));
        assertThat(detail.path("supplier").path("balance").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(detail.path("invoices")).hasSize(2);
        assertThat(detail.path("payments").get(0).path("recordedBy").asText()).isEqualTo("Admin Test");

        postJson("/suppliers", admin, Map.of("name", "BULMETI SHPK")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("supplier.nameTaken"));
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        getJson("/suppliers", cashier).andExpect(status().isForbidden());
    }

    @Test
    void reorderListGroupsLowStockByLastSupplier() throws Exception {
        String admin = registerSuperAdmin();
        long milk = product(admin, "700000000002", "100.00", "0", "pcs", "10");
        long bread = product(admin, "700000000003", "100.00", "0", "pcs", "5");
        long never = product(admin, "700000000004", "100.00", "1", "pcs", "3");
        long plenty = product(admin, "700000000005", "100.00", "50", "pcs", "3");
        purchase(admin, "F-1", "Furnitori A", LocalDate.now().minusDays(10), milk, "4", "60.00", null);
        purchase(admin, "F-2", "Furnitori B", LocalDate.now(), milk, "2", "70.00", null);
        purchase(admin, "F-3", "Furnitori A", LocalDate.now(), bread, "2", "30.00", null);

        assertThat(data(getJson("/inventory/low-stock", admin))).hasSize(3);
        JsonNode groups = data(getJson("/inventory/reorder", admin).andExpect(status().isOk()));
        assertThat(groups.get(0).path("supplierName").asText()).isEqualTo("Furnitori A");
        assertThat(groups.get(0).path("lines")).hasSize(1);
        assertThat(groups.get(0).path("lines").get(0).path("suggestedQuantity").decimalValue()).isEqualByComparingTo("8");
        assertThat(groups.get(1).path("supplierName").asText()).isEqualTo("Furnitori B");
        assertThat(groups.get(1).path("lines").get(0).path("suggestedQuantity").decimalValue()).isEqualByComparingTo("14");
        assertThat(groups.get(1).path("lines").get(0).path("lastPurchasePrice").decimalValue()).isEqualByComparingTo("70.00");
        assertThat(groups.get(2).path("supplierName").isNull()).isTrue();
        assertThat(groups.get(2).path("lines").get(0).path("productId").asLong()).isEqualTo(never);
        assertThat(plenty).isPositive();
    }

    @Test
    void stockAdjustmentsNeedAReasonAndNeverGoNegative() throws Exception {
        String admin = registerSuperAdmin();
        long p = product(admin, "700000000006", "100.00", "10", "pcs", null);

        postJson("/inventory/adjustments", admin, Map.of("productId", p, "quantityChange", "-2", "reason", "DAMAGED", "note", "Shishe e thyer"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.stockAfter").value(8));
        postJson("/inventory/adjustments", admin, Map.of("productId", p, "quantityChange", "-20", "reason", "LOST"))
                .andExpect(jsonPath("$.code").value("stock.wouldBeNegative"));
        postJson("/inventory/adjustments", admin, Map.of("productId", p, "quantityChange", "1", "reason", "OTHER"))
                .andExpect(jsonPath("$.code").value("stock.noteRequired"));
        postJson("/inventory/adjustments", admin, Map.of("productId", p, "quantityChange", "1", "reason", "COUNT_CORRECTION"))
                .andExpect(jsonPath("$.code").value("stock.countReasonReserved"));
        postJson("/inventory/adjustments", admin, Map.of("productId", p, "quantityChange", "-0.5", "reason", "DAMAGED"))
                .andExpect(status().isBadRequest());

        assertThat(stock(p)).isEqualByComparingTo("8");
        assertThat(data(getJson("/inventory/adjustments?productId=" + p, admin))).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT details FROM audit_events WHERE action = 'STOCK_ADJUSTED'", String.class))
                .contains("-2 DAMAGED - Shishe e thyer");
    }

    @Test
    void stockCountAppliesOnlyTheDifferencesOfCountedProducts() throws Exception {
        String admin = registerSuperAdmin();
        long counted = product(admin, "700000000007", "100.00", "10", "pcs", null);
        long same = product(admin, "700000000008", "100.00", "5", "pcs", null);
        long notCounted = product(admin, "700000000009", "100.00", "3", "pcs", null);

        long countId = data(postJson("/inventory/counts", admin, Map.of("note", "Inventari i tetorit")).andExpect(status().isOk())).path("id").asLong();
        postJson("/inventory/counts", admin, null).andExpect(jsonPath("$.code").value("count.alreadyOpen"));
        putJson("/inventory/counts/" + countId + "/lines", admin, Map.of("barcode", "700000000007", "countedQuantity", "7")).andExpect(status().isOk());
        putJson("/inventory/counts/" + countId + "/lines", admin, Map.of("barcode", "700000000007", "countedQuantity", "8")).andExpect(status().isOk());
        JsonNode view = data(putJson("/inventory/counts/" + countId + "/lines", admin, Map.of("productId", same, "countedQuantity", "5")));
        assertThat(view.path("lines")).hasSize(2);
        assertThat(view.path("lines").get(0).path("difference").decimalValue()).isEqualByComparingTo("-2");
        assertThat(view.path("linesWithDifference").asInt()).isEqualTo(1);

        JsonNode applied = data(postJson("/inventory/counts/" + countId + "/apply", admin, null).andExpect(status().isOk()));
        assertThat(applied.path("status").asText()).isEqualTo("APPLIED");
        assertThat(stock(counted)).isEqualByComparingTo("8");
        assertThat(stock(same)).isEqualByComparingTo("5");
        assertThat(stock(notCounted)).isEqualByComparingTo("3");
        assertThat(jdbc.queryForObject("SELECT reason FROM stock_adjustments WHERE inventory_count_id = ?", String.class, countId))
                .isEqualTo("COUNT_CORRECTION");
        putJson("/inventory/counts/" + countId + "/lines", admin, Map.of("productId", same, "countedQuantity", "1"))
                .andExpect(jsonPath("$.code").value("count.notOpen"));
        getJson("/inventory/counts/current", admin).andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void expiryEstimateAssumesOlderDeliveriesSellFirst() throws Exception {
        String admin = registerSuperAdmin();
        long yogurt = product(admin, "700000000010", "100.00", "0", "pcs", null);
        purchase(admin, "F-1", "Bulmeti", LocalDate.now().minusDays(5), yogurt, "10", "50.00", LocalDate.now().plusDays(3));
        purchase(admin, "F-2", "Bulmeti", LocalDate.now(), yogurt, "5", "50.00", LocalDate.now().plusDays(60));
        openShift(admin, "0");
        postJson("/sales/cart", admin, Map.of("productId", yogurt, "quantity", 8)).andExpect(status().isOk());
        postJson("/sales/checkout", admin, null).andExpect(status().isOk());

        JsonNode expiring = data(getJson("/inventory/expiring?days=7", admin).andExpect(status().isOk()));
        assertThat(expiring).hasSize(1);
        assertThat(expiring.get(0).path("invoiceNumber").asText()).isEqualTo("F-1");
        assertThat(expiring.get(0).path("estimatedOnShelf").decimalValue()).isEqualByComparingTo("2");
        assertThat(expiring.get(0).path("daysLeft").asLong()).isEqualTo(3);
        assertThat(data(getJson("/inventory/expiring?days=90", admin))).hasSize(2);
    }

    private static String ean13(String twelveDigits) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = twelveDigits.charAt(i) - '0';
            sum += i % 2 == 0 ? digit : digit * 3;
        }
        return twelveDigits + (10 - sum % 10) % 10;
    }

    @Test
    void scaleLabelsAddTheWeighedQuantity() throws Exception {
        String admin = registerSuperAdmin();
        product(admin, "2100123", "750.00", "10", "kg", null);
        product(admin, "2100124", "50.00", "10", "pcs", null);

        postJson("/sales/cart", admin, Map.of("barcode", ean13("210012300350"), "quantity", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].quantity").value(0.35))
                .andExpect(jsonPath("$.data[0].lineTotal").value(262.50));
        String valid = ean13("210012300350");
        String wrongCheckDigit = valid.substring(0, 12) + (char) ('0' + (valid.charAt(12) - '0' + 1) % 10);
        postJson("/sales/cart", admin, Map.of("barcode", wrongCheckDigit, "quantity", 1)).andExpect(status().isBadRequest());
        postJson("/sales/cart", admin, Map.of("barcode", ean13("210012400350"), "quantity", 1))
                .andExpect(jsonPath("$.code").value("scale.notWeighed"));
    }

    @Test
    void productsExportAndImportAsCsv() throws Exception {
        String admin = registerSuperAdmin();
        product(admin, "700000000011", "100.00", "5", "pcs", null);

        MvcResult export = mvc.perform(get("/products/export").header("X-Auth-Token", admin)).andExpect(status().isOk()).andReturn();
        String csv = export.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿barcode;name;category;unit;purchase_price;price;tax_rate;stock;min_stock;reorder_quantity;active")
                .contains("700000000011;Produkt 700000000011;Ushqime;pcs;1;100;20;5;;;true");

        String upload = "barcode;name;category;unit;purchase_price;price;tax_rate;stock;min_stock\n"
                + "700000000011;;;;;120,50;;;4\n"
                + "700000000012;\"Djathë; i bardhë\";Bulmetore;kg;600;750;20;12,5;2\n"
                + "700000000013;Gabim;;pcs;1;abc;20;1;\n";
        JsonNode result = data(mvc.perform(post("/products/import").header("X-Auth-Token", admin)
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).content(upload.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk()));
        assertThat(result.path("created").asInt()).isEqualTo(1);
        assertThat(result.path("updated").asInt()).isEqualTo(1);
        assertThat(result.path("errors")).hasSize(1);
        assertThat(result.path("errors").get(0).path("line").asInt()).isEqualTo(4);

        assertThat(jdbc.queryForObject("SELECT price FROM products WHERE barcode = '700000000011'", BigDecimal.class)).isEqualByComparingTo("120.50");
        assertThat(jdbc.queryForObject("SELECT min_stock FROM products WHERE barcode = '700000000011'", BigDecimal.class)).isEqualByComparingTo("4");
        assertThat(jdbc.queryForObject("SELECT name FROM products WHERE barcode = '700000000012'", String.class)).isEqualTo("Djathë; i bardhë");
        assertThat(stock(jdbc.queryForObject("SELECT id FROM products WHERE barcode = '700000000012'", Long.class))).isEqualByComparingTo("12.5");
    }
}
