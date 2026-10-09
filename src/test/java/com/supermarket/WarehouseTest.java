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

class WarehouseTest extends IntegrationTest {

    /** A box of a product: {pieces} pieces, own price or null. */
    private long box(String token, long productId, String barcode, int pieces, String price) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("barcode", barcode, "name", "Kuti " + pieces, "pieces", pieces));
        if (price != null) {
            body.put("price", price);
        }
        return data(postJson("/warehouse/products/" + productId + "/packages", token, body).andExpect(status().isOk()))
                .path("id").asLong();
    }

    private JsonNode scan(String token, String barcode, int quantity) throws Exception {
        return data(postJson("/sales/cart", token, Map.of("barcode", barcode, "quantity", quantity)).andExpect(status().isOk()));
    }

    private BigDecimal stock(long productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", BigDecimal.class, productId);
    }

    @Test
    void aBoxHasItsOwnBarcodeButNoStockOfItsOwn() throws Exception {
        String admin = registerSuperAdmin();
        long water = createProduct(admin, "950000000001", "100.00", 30);
        long cheese = data(postJson("/products", admin, Map.of("name", "Djathë", "barcode", "950000000002", "price", "900",
                "purchasePrice", "500", "taxRate", "20", "stock", "3.5", "unit", "kg", "categoryId", 7))).path("id").asLong();

        box(admin, water, "950000000011", 10, "900.00");
        // One code, one thing: neither another box nor a product may use it, and a box cannot take a product's code.
        postJson("/warehouse/products/" + water + "/packages", admin, Map.of("barcode", "950000000011", "name", "X", "pieces", 6))
                .andExpect(jsonPath("$.code").value("product.barcodeIsPackage"));
        postJson("/warehouse/products/" + water + "/packages", admin, Map.of("barcode", "950000000002", "name", "X", "pieces", 6))
                .andExpect(jsonPath("$.code").value("product.barcodeExists"));
        postJson("/products", admin, Map.of("name", "Tjetër", "barcode", "950000000011", "price", "1", "purchasePrice", "1",
                "taxRate", "20", "stock", "0", "unit", "pcs", "categoryId", 7)).andExpect(jsonPath("$.code").value("product.barcodeIsPackage"));
        postJson("/warehouse/products/" + cheese + "/packages", admin, Map.of("barcode", "950000000012", "name", "X", "pieces", 6))
                .andExpect(jsonPath("$.code").value("package.onlyPieces"));
        postJson("/warehouse/products/" + water + "/packages", admin, Map.of("barcode", "950000000013", "name", "X", "pieces", 1))
                .andExpect(jsonPath("$.code").value("package.invalidPieces"));

        JsonNode scanned = data(getJson("/products/scan/950000000011", admin).andExpect(status().isOk()));
        assertThat(scanned.path("product").path("id").asLong()).isEqualTo(water);
        assertThat(scanned.path("packageInfo").path("pieces").asInt()).isEqualTo(10);
        assertThat(data(getJson("/products/scan/950000000001", admin)).path("packageInfo").isNull()).isTrue();
    }

    @Test
    void sellingABoxTakesItsPiecesFromStockAndPiecesCanBeRefundedOneByOne() throws Exception {
        String admin = registerSuperAdmin();
        long water = createProduct(admin, "950000000021", "100.00", 25);
        box(admin, water, "950000000022", 10, "900.00");
        openShift(admin, "0");

        JsonNode cart = scan(admin, "950000000022", 1);
        assertThat(cart.get(0).path("packageName").asText()).isEqualTo("Kuti 10");
        assertThat(cart.get(0).path("price").decimalValue()).isEqualByComparingTo("900.00");
        cart = scan(admin, "950000000021", 6);
        assertThat(cart).hasSize(2);
        // 10 + 6 + another box of 10 = 26 > 25 in stock: refused, though each line alone would fit.
        postJson("/sales/cart", admin, Map.of("barcode", "950000000022", "quantity", 1)).andExpect(status().isBadRequest());
        long boxLine = cart.get(0).path("lineId").asLong();
        putJson("/sales/cart/lines/" + boxLine, admin, Map.of("quantity", 2)).andExpect(status().isBadRequest());

        JsonNode receipt = data(postJson("/sales/checkout", admin, null).andExpect(status().isOk()));
        assertThat(receipt.path("totalAmount").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(receipt.path("printableReceipt").asText()).contains(row("  1 Kuti 10 x 900.00", "900.00"))
                .contains(row("  6 copë x 100.00", "600.00"));
        assertThat(stock(water)).isEqualByComparingTo("9");
        Map<String, Object> boxSale = jdbc.queryForMap("SELECT id, quantity, package_count, package_price, line_total FROM sale_items WHERE package_id IS NOT NULL");
        assertThat((BigDecimal) boxSale.get("quantity")).isEqualByComparingTo("10");
        assertThat((BigDecimal) boxSale.get("package_count")).isEqualByComparingTo("1");
        assertThat((BigDecimal) boxSale.get("line_total")).isEqualByComparingTo("900.00");

        // The customer brings back 3 pieces of the opened box: 3 tenths of what the box cost.
        long saleId = jdbc.queryForObject("SELECT id FROM sales", Long.class);
        JsonNode refund = data(postJson("/sales/" + saleId + "/refunds", admin, Map.of("method", "CASH", "reason", "Kthim",
                "lines", List.of(Map.of("saleItemId", boxSale.get("id"), "quantity", 3)))).andExpect(status().isOk()));
        assertThat(refund.path("totalAmount").decimalValue()).isEqualByComparingTo("270.00");
        assertThat(stock(water)).isEqualByComparingTo("12");
    }

    @Test
    void buyXGetYCountsSinglePiecesButAPercentPromotionAlsoCoversBoxes() throws Exception {
        String admin = registerSuperAdmin();
        long juice = createProduct(admin, "950000000031", "100.00", 100);
        box(admin, juice, "950000000032", 6, null);
        postJson("/promotions", admin, Map.of("name", "2+1", "type", "BUY_X_GET_Y", "buyQuantity", 2, "freeQuantity", 1,
                "productId", juice)).andExpect(status().isOk());
        scan(admin, "950000000032", 1);
        JsonNode summary = data(getJson("/sales/cart/summary", admin));
        // The box has no price of its own: 6 x 100. "Buy 2 get 1" is not given on the box.
        assertThat(summary.path("subtotal").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(summary.path("promotionDiscount").decimalValue()).isEqualByComparingTo("0");

        postJson("/promotions", admin, Map.of("name", "-10%", "type", "PERCENT", "discountPercent", "10", "productId", juice))
                .andExpect(status().isOk());
        assertThat(data(getJson("/sales/cart/summary", admin)).path("promotionDiscount").decimalValue()).isEqualByComparingTo("60.00");
    }

    @Test
    void boxesBoughtOnAPurchaseInvoiceArriveAsPieces() throws Exception {
        String admin = registerSuperAdmin();
        long cola = createProduct(admin, "950000000041", "120.00", 4);
        long colaBox = box(admin, cola, "950000000042", 24, null);
        postJson("/purchases", admin, Map.of("invoiceNumber", "F-BOX", "company", "Coca-Cola", "invoiceDate", LocalDate.now().toString(),
                "items", List.of(Map.of("productId", cola, "packageId", colaBox, "quantity", 5, "purchasePrice", "1680.00",
                        "sellingPrice", "130.00", "taxRate", "20", "unit", "pcs")))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalAmount").value(8400.0));

        assertThat(stock(cola)).isEqualByComparingTo("124");
        assertThat(jdbc.queryForObject("SELECT purchase_price FROM products WHERE id = ?", BigDecimal.class, cola)).isEqualByComparingTo("70.00");
        assertThat(jdbc.queryForObject("SELECT package_count FROM purchase_items", BigDecimal.class)).isEqualByComparingTo("5");

        JsonNode card = data(getJson("/warehouse/products/" + cola, admin).andExpect(status().isOk()));
        JsonNode purchase = card.path("movements").get(0);
        assertThat(purchase.path("type").asText()).isEqualTo("PURCHASE");
        assertThat(purchase.path("change").decimalValue()).isEqualByComparingTo("120");
        assertThat(purchase.path("stockAfter").decimalValue()).isEqualByComparingTo("124");
        assertThat(card.path("suppliers").get(0).path("supplier").asText()).isEqualTo("Coca-Cola");
        assertThat(card.path("suppliers").get(0).path("lastPurchasePrice").decimalValue()).isEqualByComparingTo("70.00");
        JsonNode change = card.path("priceHistory").get(0);
        assertThat(change.path("source").asText()).isEqualTo("PURCHASE");
        assertThat(change.path("newPrice").decimalValue()).isEqualByComparingTo("130.00");
        assertThat(change.path("newPurchasePrice").decimalValue()).isEqualByComparingTo("70.00");
    }

    @Test
    void pricesAreChangedInBulkOnlyWhenApplied() throws Exception {
        String admin = registerSuperAdmin();
        long a = createProduct(admin, "950000000051", "100.00", 5);
        long b = createProduct(admin, "950000000052", "237.00", 5);
        Map<String, Object> request = new HashMap<>(Map.of("productIds", List.of(a, b), "percent", "10", "roundTo", "10", "apply", false));

        JsonNode preview = data(postJson("/warehouse/prices/bulk", admin, request).andExpect(status().isOk()));
        assertThat(preview.get(1).path("newPrice").decimalValue()).isEqualByComparingTo("260.00");
        assertThat(jdbc.queryForObject("SELECT price FROM products WHERE id = ?", BigDecimal.class, b)).isEqualByComparingTo("237.00");

        request.put("apply", true);
        postJson("/warehouse/prices/bulk", admin, request).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT price FROM products WHERE id = ?", BigDecimal.class, a)).isEqualByComparingTo("110.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_changes WHERE source = 'BULK'", Integer.class)).isEqualTo(2);
        assertThat(data(getJson("/warehouse/labels/changed-today", admin))).hasSize(2);
        assertThat(data(getJson("/warehouse/overview", admin)).path("pricesChangedToday").asInt()).isEqualTo(2);

        request.put("percent", "-95");
        postJson("/warehouse/prices/bulk", admin, request).andExpect(jsonPath("$.code").value("warehouse.invalidPercent"));
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        getJson("/warehouse/products", cashier).andExpect(status().isForbidden());
    }

    @Test
    void shelfLabelsShowThePricePerLitreAndPerPieceOfABox() throws Exception {
        String admin = registerSuperAdmin();
        long water = data(postJson("/products", admin, Map.of("name", "Ujë 0.5 l", "barcode", "5901234123457", "price", "60",
                "purchasePrice", "30", "taxRate", "20", "stock", "50", "unit", "pcs", "categoryId", 7,
                "contentAmount", "500", "contentUnit", "ml"))).path("id").asLong();
        long six = box(admin, water, "950000000061", 6, "300.00");

        JsonNode labels = data(postJson("/warehouse/labels/preview", admin, Map.of("items", List.of(
                Map.of("productId", water, "copies", 2), Map.of("productId", water, "packageId", six)))).andExpect(status().isOk()));
        assertThat(labels.get(0).path("unitPriceText").asText()).isEqualTo("për litër 120");
        assertThat(labels.get(0).path("copies").asInt()).isEqualTo(2);
        assertThat(labels.get(1).path("packageText").asText()).isEqualTo("Kuti 6");
        assertThat(labels.get(1).path("unitPriceText").asText()).isEqualTo("copa 50 | për litër 100");
        assertThat(labels.get(1).path("barcode").asText()).isEqualTo("950000000061");

        postJson("/products", admin, Map.of("name", "X", "barcode", "950000000062", "price", "1", "purchasePrice", "1", "taxRate", "20",
                "stock", "0", "unit", "pcs", "categoryId", 7, "contentAmount", "1", "contentUnit", "cup"))
                .andExpect(jsonPath("$.code").value("product.invalidContent"));
    }
}
