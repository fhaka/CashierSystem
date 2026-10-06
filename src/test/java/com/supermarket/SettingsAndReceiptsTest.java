package com.supermarket;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SettingsAndReceiptsTest extends IntegrationTest {

    private static Map<String, Object> shop(int width) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("name", "Haka Market");
        settings.put("address", "Rruga e Durrësit 10");
        settings.put("city", "Tiranë");
        settings.put("taxId", "L12345678A");
        settings.put("phone", "069 123 4567");
        settings.put("email", "");
        settings.put("receiptFooter", "Ju presim përsëri!");
        settings.put("receiptWidth", width);
        settings.put("autoPrint", true);
        settings.put("receiptPrinter", "");
        return settings;
    }

    private String sell(String token, long productId) throws Exception {
        postJson("/sales/cart", token, Map.of("productId", productId, "quantity", 2)).andExpect(status().isOk());
        return data(postJson("/sales/checkout", token, null).andExpect(status().isOk())).path("printableReceipt").asText();
    }

    @Test
    void defaultsUntilTheAdministratorSavesTheShopDetails() throws Exception {
        getJson("/auth/setup", null).andExpect(jsonPath("$.data.shopName").value("Supermarket"));
        String admin = registerSuperAdmin();
        String manager = createUserAndLogin(admin, "shef", "SUPER_CASHIER");
        getJson("/settings/shop", manager).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiptWidth").value(32))
                .andExpect(jsonPath("$.data.autoPrint").value(false));

        putJson("/settings/shop", manager, shop(42)).andExpect(status().isForbidden());
        putJson("/settings/shop", admin, shop(40)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("settings.invalidWidth"));
        Map<String, Object> noName = shop(42);
        noName.put("name", "  ");
        putJson("/settings/shop", admin, noName).andExpect(jsonPath("$.code").value("settings.nameRequired"));

        putJson("/settings/shop", admin, shop(42)).andExpect(status().isOk());
        putJson("/settings/shop", admin, shop(42)).andExpect(status().isOk());
        getJson("/settings/shop", manager).andExpect(jsonPath("$.data.name").value("Haka Market"))
                .andExpect(jsonPath("$.data.taxId").value("L12345678A"))
                .andExpect(jsonPath("$.data.autoPrint").value(true));
        getJson("/auth/setup", null).andExpect(jsonPath("$.data.shopName").value("Haka Market"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'SETTINGS_CHANGED'", Integer.class)).isEqualTo(2);

        String preview = data(postJson("/settings/shop/preview", admin, shop(32)).andExpect(status().isOk()))
                .path("receiptText").asText();
        assertThat(preview).startsWith("          HAKA MARKET\n").contains("Ju presim përsëri!");
    }

    @Test
    void receiptsCarryTheShopHeaderAndFooterAtTheChosenWidth() throws Exception {
        String admin = registerSuperAdmin();
        putJson("/settings/shop", admin, shop(42)).andExpect(status().isOk());
        long p = createProduct(admin, "910000000001", "120.00", 10);
        openShift(admin, "0");

        String receipt = sell(admin, p);
        assertThat(receipt.lines().toList()).allMatch(line -> line.length() <= 42);
        assertThat(receipt).startsWith("               HAKA MARKET\n")
                .contains("Rruga e Durrësit 10, Tiranë")
                .contains("NIPT: L12345678A")
                .contains("Tel: 069 123 4567")
                .contains("=".repeat(42) + "\n")
                .contains("  2 copë x 120.00" + " ".repeat(19) + "240.00\n")
                .endsWith("Ju presim përsëri!\n")
                .doesNotContain("Faleminderit");
    }

    @Test
    void reprintsAreIdenticalCopiesAndOnlyOfYourOwnSales() throws Exception {
        String admin = registerSuperAdmin();
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long p = createProduct(admin, "910000000002", "50.00", 10);
        openShift(admin, "0");
        String original = sell(admin, p);
        long saleId = jdbc.queryForObject("SELECT id FROM sales", Long.class);

        // Later changes to the shop details do not change an old receipt.
        putJson("/settings/shop", admin, shop(32)).andExpect(status().isOk());
        String copy = data(getJson("/sales/" + saleId + "/receipt", admin).andExpect(status().isOk())).path("receiptText").asText();
        String mark = "         *** KOPJE ***\n";
        assertThat(copy).isEqualTo(mark + original + mark);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action = 'RECEIPT_REPRINTED'", Integer.class)).isEqualTo(1);

        getJson("/sales/" + saleId + "/receipt", cashier).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("sale.onlyOwn"));

        // Sales from before receipts were saved are rebuilt from the sale.
        jdbc.update("UPDATE sales SET receipt_text = NULL");
        JsonNode rebuilt = data(getJson("/sales/" + saleId + "/receipt", admin).andExpect(status().isOk()));
        assertThat(rebuilt.path("receiptText").asText()).startsWith(mark + "          HAKA MARKET\n")
                .contains(row("  2 copë x 50.00", "100.00"));
    }
}
