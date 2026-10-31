package com.supermarket;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PurchaseOrdersTest extends IntegrationTest {

    private long product(String token, String barcode, int stock, String minStock) throws Exception {
        return data(postJson("/products", token, Map.of("name", "Artikull " + barcode, "barcode", barcode, "price", "100.00",
                "purchasePrice", "60.00", "taxRate", "20", "stock", stock, "unit", "pcs", "categoryId", 7, "minStock", minStock)))
                .path("id").asLong();
    }

    private static Map<String, Object> line(long productId, Object packageId, String quantity, String price) {
        Map<String, Object> line = new HashMap<>(Map.of("productId", productId, "quantity", quantity));
        if (packageId != null) {
            line.put("packageId", packageId);
        }
        if (price != null) {
            line.put("price", price);
        }
        return line;
    }

    @Test
    void anOrderIsWrittenSentAndClosedWithoutTouchingStock() throws Exception {
        String admin = registerSuperAdmin();
        long water = product(admin, "990000000001", 2, "10");
        long juice = product(admin, "990000000002", 0, "5");
        long box = data(postJson("/warehouse/products/" + water + "/packages", admin,
                Map.of("name", "Pako 6", "barcode", "990000000011", "pieces", 6, "price", "330"))).path("id").asLong();
        long supplier = data(postJson("/suppliers", admin, Map.of("name", "Glina shpk", "phone", "0691234567"))).path("id").asLong();

        // 3 boxes of water at 300 each, 10 juices at the average cost (60) since no price is given.
        JsonNode order = data(postJson("/purchase-orders", admin, Map.of("supplierId", supplier, "note", "Sjellini të hënën",
                "lines", List.of(line(water, box, "3", "300"), line(juice, null, "10", null)))).andExpect(status().isOk()));
        assertThat(order.path("number").asText()).isEqualTo("P000001");
        assertThat(order.path("status").asText()).isEqualTo("OPEN");
        assertThat(order.path("supplierPhone").asText()).isEqualTo("0691234567");
        assertThat(order.path("total").decimalValue()).isEqualByComparingTo("1500");          // 900 + 600
        assertThat(order.path("lines").get(0).path("pieceQuantity").decimalValue()).isEqualByComparingTo("18");
        assertThat(order.path("lines").get(1).path("price").decimalValue()).isEqualByComparingTo("60");
        long id = order.path("id").asLong();

        // The reorder list shows what is already ordered and suggests only the rest.
        JsonNode reorder = data(getJson("/inventory/reorder", admin));
        JsonNode waterLine = null;
        for (JsonNode group : reorder) {
            for (JsonNode l : group.path("lines")) {
                if (l.path("productId").asLong() == water) {
                    waterLine = l;
                }
            }
        }
        assertThat(waterLine).isNotNull();
        assertThat(waterLine.path("onOrder").decimalValue()).isEqualByComparingTo("18");
        assertThat(waterLine.path("suggestedQuantity").decimalValue()).isEqualByComparingTo("0");   // up to twice the minimum: 20 - 2 in stock - 18 ordered

        // Sent, then corrected after the supplier's call; stock never moves.
        postJson("/purchase-orders/" + id + "/sent", admin, null).andExpect(jsonPath("$.data.status").value("SENT"));
        putJson("/purchase-orders/" + id, admin, Map.of("supplierId", supplier, "lines", List.of(line(water, box, "4", "300"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.lineCount").value(1)).andExpect(jsonPath("$.data.status").value("SENT"));
        assertThat(jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", java.math.BigDecimal.class, water)).isEqualByComparingTo("2");

        assertThat(data(getJson("/purchase-orders?status=ACTIVE", admin))).hasSize(1);
        postJson("/purchase-orders/" + id + "/received", admin, null).andExpect(jsonPath("$.data.status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.closedBy").value("Admin Test"));
        assertThat(data(getJson("/purchase-orders?status=ACTIVE", admin))).isEmpty();
        postJson("/purchase-orders/" + id + "/cancel", admin, null).andExpect(jsonPath("$.code").value("order.closed"));
        putJson("/purchase-orders/" + id, admin, Map.of("supplierId", supplier, "lines", List.of(line(juice, null, "1", null))))
                .andExpect(jsonPath("$.code").value("order.closed"));
        // Received: nothing is on order any more.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_orders WHERE status = 'RECEIVED'", Integer.class)).isEqualTo(1);
        assertThat(data(postJson("/purchase-orders", admin, Map.of("supplierId", supplier, "lines", List.of(line(juice, null, "1", null)))))
                .path("number").asText()).isEqualTo("P000002");
    }

    @Test
    void ordersAreCheckedAndOnlyForManagers() throws Exception {
        String admin = registerSuperAdmin();
        long p = product(admin, "990000000003", 5, "1");
        long supplier = data(postJson("/suppliers", admin, Map.of("name", "Furnitori X"))).path("id").asLong();

        postJson("/purchase-orders", admin, Map.of("lines", List.of(line(p, null, "1", null))))
                .andExpect(jsonPath("$.code").value("order.supplierRequired"));
        postJson("/purchase-orders", admin, Map.of("supplierId", supplier, "lines", List.of()))
                .andExpect(jsonPath("$.code").value("order.linesRequired"));
        postJson("/purchase-orders", admin, Map.of("supplierId", supplier, "lines", List.of(line(p, null, "1", null), line(p, null, "2", null))))
                .andExpect(jsonPath("$.code").value("order.duplicateLine"));
        postJson("/purchase-orders", admin, Map.of("supplierId", supplier, "lines", List.of(line(p, null, "0", null))))
                .andExpect(status().isBadRequest());
        postJson("/purchase-orders", admin, Map.of("supplierId", supplier, "lines", List.of(line(p, 999, "1", null))))
                .andExpect(jsonPath("$.code").value("package.notFound"));
        getJson("/purchase-orders?status=LOST", admin).andExpect(jsonPath("$.code").value("order.invalidStatus"));

        long id = data(postJson("/purchase-orders", admin, Map.of("supplierId", supplier, "lines", List.of(line(p, null, "1", null)))))
                .path("id").asLong();
        postJson("/purchase-orders/" + id + "/cancel", admin, null).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        postJson("/purchase-orders/" + id + "/sent", admin, null).andExpect(jsonPath("$.code").value("order.closed"));

        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        getJson("/purchase-orders", cashier).andExpect(status().isForbidden());
        postJson("/purchase-orders", cashier, Map.of("supplierId", supplier, "lines", List.of(line(p, null, "1", null))))
                .andExpect(status().isForbidden());
    }
}
