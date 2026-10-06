package com.supermarket;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SellingTest extends IntegrationTest {

    private long createProduct(String token, String barcode, String price, String stock, String unit, String taxRate) throws Exception {
        return data(postJson("/products", token, Map.of(
                "name", "Produkt " + barcode, "barcode", barcode, "price", price, "purchasePrice", "1.00",
                "taxRate", taxRate, "stock", stock, "unit", unit, "categoryId", 7
        )).andExpect(status().isOk())).path("id").asLong();
    }

    private BigDecimal stock(long productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", BigDecimal.class, productId);
    }

    @Test
    void sellsByWeight() throws Exception {
        String admin = registerSuperAdmin();
        long cheese = createProduct(admin, "400000000001", "750.00", "10.000", "kg", "20");
        openShift(admin, "0");

        postJson("/sales/cart", admin, Map.of("productId", cheese, "quantity", "0.350"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].lineTotal").value(262.50));

        JsonNode receipt = data(postJson("/sales/checkout", admin, null).andExpect(status().isOk()));
        assertThat(receipt.path("totalAmount").decimalValue()).isEqualByComparingTo("262.50");
        assertThat(receipt.path("printableReceipt").asText()).contains("  0.350 kg x 750.00 = 262.50");
        assertThat(stock(cheese)).isEqualByComparingTo("9.650");
    }

    @Test
    void piecesMustBeWholeAndKilogramsHaveAtMostThreeDecimals() throws Exception {
        String admin = registerSuperAdmin();
        long bread = createProduct(admin, "400000000002", "100.00", "10", "pcs", "20");
        long cheese = createProduct(admin, "400000000003", "750.00", "10", "kg", "20");

        postJson("/sales/cart", admin, Map.of("productId", bread, "quantity", "1.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Produktet me copë shiten vetëm me numra të plotë"));
        postJson("/sales/cart", admin, Map.of("productId", cheese, "quantity", "0.3505"))
                .andExpect(status().isBadRequest());
        postJson("/products", admin, Map.of("name", "X", "barcode", "400000000004", "price", "1", "purchasePrice", "1",
                "taxRate", "20", "stock", "2.5", "unit", "pcs")).andExpect(status().isBadRequest());
    }

    @Test
    void twoTillsCannotSellTheLastItemTwice() throws Exception {
        String admin = registerSuperAdmin();
        long product = createProduct(admin, "400000000005", "100.00", "1", "pcs", "20");
        List<String> tills = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            String cashier = createUserAndLogin(admin, "arka" + i, "CASHIER");
            openShift(cashier, "0");
            postJson("/sales/cart", cashier, Map.of("productId", product, "quantity", 1)).andExpect(status().isOk());
            tills.add(cashier);
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tills.size());
        List<Future<Integer>> results = new ArrayList<>();
        for (String till : tills) {
            Callable<Integer> checkout = () -> {
                start.await();
                return postJson("/sales/checkout", till, null).andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(checkout));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        pool.shutdown();

        assertThat(statuses).containsOnlyOnce(200);
        assertThat(stock(product)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales", Integer.class)).isEqualTo(1);
    }

    @Test
    void cartIsKeptInTheDatabaseAcrossSignIns() throws Exception {
        String admin = registerSuperAdmin();
        long product = createProduct(admin, "400000000006", "10.00", "10", "pcs", "20");
        postJson("/sales/cart", admin, Map.of("productId", product, "quantity", 3)).andExpect(status().isOk());

        String secondLogin = login("admin", "admin-pass");
        getJson("/sales/cart", secondLogin).andExpect(jsonPath("$.data[0].quantity").value(3));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cart_items", Integer.class)).isEqualTo(1);
    }

    @Test
    void changingQuantityToZeroRemovesTheLine() throws Exception {
        String admin = registerSuperAdmin();
        long first = createProduct(admin, "400000000007", "10.00", "10", "pcs", "20");
        long second = createProduct(admin, "400000000008", "20.00", "10", "pcs", "20");
        postJson("/sales/cart", admin, Map.of("productId", first, "quantity", 1));
        postJson("/sales/cart", admin, Map.of("productId", second, "quantity", 2));
        putJson("/sales/cart/" + second, admin, Map.of("price", "15.00", "quantity", 2)).andExpect(status().isOk());

        putJson("/sales/cart/" + first, admin, Map.of("quantity", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].price").value(15.00));
    }

    @Test
    void cartCanBeParkedAndResumedOnAnotherTill() throws Exception {
        String admin = registerSuperAdmin();
        String otherTill = createUserAndLogin(admin, "arka", "CASHIER");
        long product = createProduct(admin, "400000000009", "10.00", "10", "pcs", "20");
        postJson("/sales/cart", admin, Map.of("productId", product, "quantity", 2));

        postJson("/sales/cart/park", admin, Map.of("label", "Zonja me pallto")).andExpect(status().isOk());
        getJson("/sales/cart", admin).andExpect(jsonPath("$.data").isEmpty());
        JsonNode parked = data(getJson("/sales/carts/parked", otherTill).andExpect(status().isOk()));
        assertThat(parked).hasSize(1);
        assertThat(parked.get(0).path("label").asText()).isEqualTo("Zonja me pallto");
        assertThat(parked.get(0).path("total").decimalValue()).isEqualByComparingTo("20.00");

        long cartId = parked.get(0).path("id").asLong();
        postJson("/sales/carts/" + cartId + "/resume", otherTill, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].quantity").value(2));
        getJson("/sales/carts/parked", admin).andExpect(jsonPath("$.data").isEmpty());
        postJson("/sales/carts/" + cartId + "/resume", admin, null).andExpect(status().isBadRequest());
    }

    @Test
    void cannotResumeWhileTheTillHasItems() throws Exception {
        String admin = registerSuperAdmin();
        long product = createProduct(admin, "400000000010", "10.00", "10", "pcs", "20");
        postJson("/sales/cart", admin, Map.of("productId", product, "quantity", 1));
        long cartId = data(postJson("/sales/cart/park", admin, null)).path("id").asLong();
        postJson("/sales/cart", admin, Map.of("productId", product, "quantity", 1));

        postJson("/sales/carts/" + cartId + "/resume", admin, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Përfundoni ose parkoni shportën aktuale para se të vazhdoni një tjetër"));
        postJson("/sales/cart/park", admin, null).andExpect(status().isOk());
        postJson("/sales/cart/park", admin, null).andExpect(status().isBadRequest());
    }

    @Test
    void invoiceNumbersAreConsecutiveAndFailedCheckoutsUseNone() throws Exception {
        String admin = registerSuperAdmin();
        long product = createProduct(admin, "400000000011", "10.00", "3", "pcs", "20");
        openShift(admin, "0");

        getJson("/sales/next-invoice-number", admin).andExpect(jsonPath("$.data").value("000001"));
        postJson("/sales/cart", admin, Map.of("productId", product, "quantity", 1));
        assertThat(data(postJson("/sales/checkout", admin, null)).path("invoiceNumber").asText()).isEqualTo("000001");

        postJson("/sales/cart", admin, Map.of("productId", product, "quantity", 2));
        jdbc.update("UPDATE products SET stock = 1 WHERE id = ?", product);
        postJson("/sales/checkout", admin, null).andExpect(status().isBadRequest());

        putJson("/sales/cart/" + product, admin, Map.of("quantity", 1)).andExpect(status().isOk());
        assertThat(data(postJson("/sales/checkout", admin, null)).path("invoiceNumber").asText()).isEqualTo("000002");
    }

    @Test
    void discountIsSpreadOverLinesAndVatFollowsIt() throws Exception {
        String admin = registerSuperAdmin();
        long tv = createProduct(admin, "400000000012", "18000.00", "5", "pcs", "20");
        long cigarettes = createProduct(admin, "400000000013", "6000.00", "5", "pcs", "0");
        openShift(admin, "0");
        postJson("/sales/cart", admin, Map.of("productId", tv, "quantity", 1));
        postJson("/sales/cart", admin, Map.of("productId", cigarettes, "quantity", 1));

        JsonNode receipt = data(postJson("/sales/checkout", admin, null).andExpect(status().isOk()));

        assertThat(receipt.path("subtotal").decimalValue()).isEqualByComparingTo("24000.00");
        assertThat(receipt.path("discountAmount").decimalValue()).isEqualByComparingTo("2400.00");
        assertThat(receipt.path("totalAmount").decimalValue()).isEqualByComparingTo("21600.00");
        JsonNode tvLine = receipt.path("items").get(0);
        assertThat(tvLine.path("discountAmount").decimalValue()).isEqualByComparingTo("1800.00");
        assertThat(tvLine.path("lineTotal").decimalValue()).isEqualByComparingTo("16200.00");
        assertThat(tvLine.path("taxAmount").decimalValue()).isEqualByComparingTo("2700.00");
        assertThat(receipt.path("items").get(1).path("lineTotal").decimalValue()).isEqualByComparingTo("5400.00");
        assertThat(receipt.path("items").get(1).path("taxAmount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(receipt.path("vatSummary")).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT SUM(line_total) FROM sale_items", BigDecimal.class)).isEqualByComparingTo("21600.00");
    }

    @Test
    void saleRemembersTheCostPriceAtTheTimeOfSale() throws Exception {
        String admin = registerSuperAdmin();
        long product = createProduct(admin, "400000000014", "10.00", "5", "pcs", "20");
        openShift(admin, "0");
        postJson("/sales/cart", admin, Map.of("productId", product, "quantity", 1));
        postJson("/sales/checkout", admin, null).andExpect(status().isOk());
        jdbc.update("UPDATE products SET purchase_price = 9.00 WHERE id = ?", product);

        assertThat(jdbc.queryForObject("SELECT purchase_price FROM sale_items", BigDecimal.class)).isEqualByComparingTo("1.00");
    }

    @Test
    void soldProductIsDeactivatedInsteadOfDeleted() throws Exception {
        String admin = registerSuperAdmin();
        long product = createProduct(admin, "400000000015", "10.00", "5", "pcs", "20");
        openShift(admin, "0");
        postJson("/sales/cart", admin, Map.of("productId", product, "quantity", 1));
        postJson("/sales/checkout", admin, null).andExpect(status().isOk());

        deleteJson("/products/" + product, admin).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT active FROM products WHERE id = ?", Boolean.class, product)).isFalse();
        postJson("/sales/cart", admin, Map.of("productId", product, "quantity", 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Produkti \"Produkt 400000000015\" nuk shitet më (është çaktivizuar)"));
        getJson("/products/in-stock", admin).andExpect(jsonPath("$.data").isEmpty());
        postJson("/products", admin, Map.of("name", "New", "barcode", "400000000015", "price", "1", "purchasePrice", "1",
                "taxRate", "20", "stock", "1", "unit", "pcs")).andExpect(status().isBadRequest());

        postJson("/products/" + product + "/activate", admin, null).andExpect(status().isOk());
        postJson("/sales/cart", admin, Map.of("productId", product, "quantity", 1)).andExpect(status().isOk());
    }
}
