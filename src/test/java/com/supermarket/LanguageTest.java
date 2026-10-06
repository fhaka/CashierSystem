package com.supermarket;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LanguageTest extends IntegrationTest {

    @Test
    void errorsAreInAlbanianByDefault() throws Exception {
        getJson("/products", null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Ju lutemi identifikohuni për të vazhduar"));
    }

    @Test
    void errorsAreInEnglishWhenTheTillAsksForIt() throws Exception {
        mvc.perform(post("/auth/login").header("Accept-Language", "en")
                        .contentType("application/json")
                        .content("{\"username\":\"nobody\",\"password\":\"wrong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    void unsupportedLanguageFallsBackToAlbanian() throws Exception {
        mvc.perform(post("/auth/login").header("Accept-Language", "de")
                        .contentType("application/json")
                        .content("{\"username\":\"nobody\",\"password\":\"wrong\"}"))
                .andExpect(jsonPath("$.message").value("Emri i përdoruesit ose fjalëkalimi është i pasaktë"));
    }

    @Test
    void messagesWithValuesShowTheValue() throws Exception {
        String admin = registerSuperAdmin();
        long productId = createProduct(admin, "300000000001", "10.00", 1);
        jdbc.update("UPDATE products SET name = 'Djathë' WHERE id = ?", productId);

        postJson("/sales/cart", admin, Map.of("productId", productId, "quantity", 5))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Nuk ka gjendje të mjaftueshme për produktin: Djathë"));
        postJson("/sales/cart", admin, Map.of("barcode", "999", "quantity", 1))
                .andExpect(jsonPath("$.message").value("Produkti me barkod 999 nuk është i regjistruar"));
    }

    @Test
    void receiptIsPrintedInAlbanian() throws Exception {
        String admin = registerSuperAdmin();
        long productId = createProduct(admin, "300000000002", "120.00", 5);
        openShift(admin, "0");
        postJson("/sales/cart", admin, Map.of("productId", productId, "quantity", 2)).andExpect(status().isOk());

        JsonNode receipt = data(postJson("/sales/checkout", admin, null).andExpect(status().isOk()));
        String text = receipt.path("printableReceipt").asText();

        assertThat(text)
                .startsWith("          SUPERMARKET\n")
                .contains("KUPON SHITJEJE\n")
                .contains(row("Nr. faturës", "000001"))
                .contains(row("Arkëtari", "Admin Test"))
                .contains(row("  2 copë x 120.00", "240.00"))
                .contains(row("TOTALI LEK", "240.00"))
                .contains(row("TVSH 20% (Baza 200.00)", "40.00"))
                .contains("Faleminderit për blerjen!")
                .containsPattern("Data +\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}");
    }
}
