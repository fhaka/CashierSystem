package com.supermarket;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportsTest extends IntegrationTest {

    private void sell(String token, long productId, int quantity) throws Exception {
        postJson("/sales/cart", token, Map.of("productId", productId, "quantity", quantity)).andExpect(status().isOk());
        postJson("/sales/checkout", token, null).andExpect(status().isOk());
    }

    private byte[] download(String path, String token) throws Exception {
        return getJson(path, token).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andReturn().getResponse().getContentAsByteArray();
    }

    @Test
    void analyticsCountsVatCostProfitAndRefunds() throws Exception {
        String admin = registerSuperAdmin();
        // 120 with 20% VAT inside; bought at 50 (VAT included) = 41.67 without VAT.
        long milk = createProduct(admin, "900000000001", "120.00", 10);
        long unsold = createProduct(admin, "900000000002", "80.00", 4);
        openShift(admin, "0");
        sell(admin, milk, 3);

        long saleId = jdbc.queryForObject("SELECT id FROM sales", Long.class);
        long saleItemId = jdbc.queryForObject("SELECT id FROM sale_items", Long.class);
        postJson("/sales/" + saleId + "/refunds", admin, Map.of("lines", List.of(Map.of("saleItemId", saleItemId, "quantity", 1)),
                "method", "CASH", "reason", "Test")).andExpect(status().isOk());

        JsonNode a = data(getJson("/reports/analytics", admin).andExpect(status().isOk()));
        JsonNode k = a.path("kpis");
        assertThat(k.path("salesCount").asLong()).isEqualTo(1);
        assertThat(k.path("grossSales").decimalValue()).isEqualByComparingTo("360.00");
        assertThat(k.path("refunds").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(k.path("refundsCount").asInt()).isEqualTo(1);
        assertThat(k.path("revenue").decimalValue()).isEqualByComparingTo("240.00");
        assertThat(k.path("vat").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(k.path("cost").decimalValue()).isEqualByComparingTo("83.33");
        assertThat(k.path("profit").decimalValue()).isEqualByComparingTo("116.67");
        assertThat(k.path("piecesSold").decimalValue()).isEqualByComparingTo("2");

        JsonNode product = a.path("products").get(0);
        assertThat(product.path("productId").asLong()).isEqualTo(milk);
        assertThat(product.path("quantity").decimalValue()).isEqualByComparingTo("2");
        assertThat(a.path("byDay").get(0).path("day").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(a.path("byPayment").get(0).path("name").asText()).isEqualTo("CASH");
        assertThat(a.path("deadStock")).hasSize(1);
        assertThat(a.path("deadStock").get(0).path("productId").asLong()).isEqualTo(unsold);

        // A period without sales is empty, not an error.
        JsonNode empty = data(getJson("/reports/analytics?from=2020-01-01&to=2020-01-31", admin).andExpect(status().isOk()));
        assertThat(empty.path("kpis").path("salesCount").asLong()).isZero();
        assertThat(empty.path("products")).isEmpty();
    }

    @Test
    void salesLogIsPagedFilteredAndCashiersSeeOnlyTheirOwn() throws Exception {
        String admin = registerSuperAdmin();
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long p = createProduct(admin, "900000000003", "100.00", 100);
        openShift(admin, "0");
        openShift(cashier, "0");
        for (int i = 0; i < 3; i++) {
            sell(admin, p, 1);
        }
        sell(cashier, p, 2);

        getJson("/sales?size=2", admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.totalCount").value(4))
                .andExpect(jsonPath("$.data.totalAmount").value(500.0))
                .andExpect(jsonPath("$.data.rows[0].invoiceNumber").value("000004"));
        getJson("/sales?size=2&page=1", admin).andExpect(jsonPath("$.data.rows.length()").value(2));
        getJson("/sales?invoice=2", admin).andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.rows[0].invoiceNumber").value("000002"));
        getJson("/sales?cashierName=arka", admin).andExpect(jsonPath("$.data.totalCount").value(1));
        getJson("/sales?from=2020-01-01&to=2020-01-02", admin).andExpect(jsonPath("$.data.totalCount").value(0));

        // A cashier's filter for another cashier is ignored: always their own sales.
        getJson("/sales?cashierName=Admin", cashier).andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.rows[0].totalAmount").value(200.0));

        long own = jdbc.queryForObject("SELECT s.id FROM sales s JOIN cashiers c ON c.id = s.cashier_id WHERE c.username = 'arka'", Long.class);
        long other = jdbc.queryForObject("SELECT MIN(id) FROM sales", Long.class);
        getJson("/sales/" + own, cashier).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        getJson("/sales/" + other, cashier).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("sale.onlyOwn"));
        getJson("/sales/" + other, admin).andExpect(status().isOk());

        getJson("/reports/dashboard", cashier).andExpect(jsonPath("$.data.salesToday").value(1))
                .andExpect(jsonPath("$.data.revenueToday").value(200.0));
        getJson("/reports/dashboard", admin).andExpect(jsonPath("$.data.salesToday").value(4))
                .andExpect(jsonPath("$.data.activeProducts").value(1));
        getJson("/reports/analytics", cashier).andExpect(status().isForbidden());
        getJson("/reports/sales.xlsx", cashier).andExpect(status().isForbidden());
    }

    @Test
    void exportsAreRealExcelAndPdfFiles() throws Exception {
        String admin = registerSuperAdmin();
        long p = createProduct(admin, "900000000004", "250.00", 10);
        openShift(admin, "0");
        sell(admin, p, 2);

        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(download("/reports/analytics.xlsx", admin)))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(8);
            assertThat(book.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).startsWith("Raporti i shitjeve");
        }
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(download("/reports/sales.xlsx", admin)))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(1);
            assertThat(book.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("000001");
        }
        assertThat(new String(download("/reports/analytics.pdf", admin), 0, 5)).isEqualTo("%PDF-");
        assertThat(new String(download("/reports/daily.pdf", admin), 0, 5)).isEqualTo("%PDF-");

        getJson("/reports/email-settings", admin).andExpect(jsonPath("$.data.configured").value(false));
        postJson("/reports/daily/email", admin, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("email.notConfigured"));
    }
}
