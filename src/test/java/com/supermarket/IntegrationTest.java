package com.supermarket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the whole application on an in-memory database built by the real Flyway migrations
 * and drives it through HTTP, the same way the cashier screen does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    private static final List<String> TABLES_CHILD_FIRST = List.of(
            "sale_logs", "sale_items", "sales", "shifts",
            "purchase_items", "purchase_invoices", "products", "auth_sessions", "cashiers", "backup_logs"
    );

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        TABLES_CHILD_FIRST.forEach(table -> jdbc.update("DELETE FROM " + table));
    }

    protected ResultActions call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        request.contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            request.header("X-Auth-Token", token);
        }
        if (body != null) {
            request.content(objectMapper.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    protected ResultActions getJson(String path, String token) throws Exception {
        return call(get(path), token, null);
    }

    protected ResultActions postJson(String path, String token, Object body) throws Exception {
        return call(post(path), token, body);
    }

    protected ResultActions putJson(String path, String token, Object body) throws Exception {
        return call(put(path), token, body);
    }

    protected ResultActions deleteJson(String path, String token) throws Exception {
        return call(delete(path), token, null);
    }

    protected JsonNode data(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("data");
    }

    /** First account on an empty database becomes the Super Admin. */
    protected String registerSuperAdmin() throws Exception {
        ResultActions result = postJson("/auth/register", null,
                Map.of("fullName", "Admin Test", "username", "admin", "password", "admin-pass"))
                .andExpect(status().isOk());
        return data(result).path("token").asText();
    }

    protected String createUserAndLogin(String adminToken, String username, String role) throws Exception {
        postJson("/users", adminToken, Map.of(
                "fullName", username + " Test",
                "username", username,
                "password", "secret-pass",
                "role", role
        )).andExpect(status().isOk());
        return login(username, "secret-pass");
    }

    protected String login(String username, String password) throws Exception {
        ResultActions result = postJson("/auth/login", null, Map.of("username", username, "password", password))
                .andExpect(status().isOk());
        return data(result).path("token").asText();
    }

    protected long createProduct(String token, String barcode, String price, int stock) throws Exception {
        ResultActions result = postJson("/products", token, Map.of(
                "name", "Produkt " + barcode,
                "barcode", barcode,
                "price", price,
                "purchasePrice", "50.00",
                "taxRate", "20",
                "stock", stock,
                "unit", "pcs",
                "categoryId", 7
        )).andExpect(status().isOk());
        return data(result).path("id").asLong();
    }

    protected long openShift(String token, String openingCash) throws Exception {
        ResultActions result = postJson("/shifts/open", token, Map.of("openingCash", openingCash))
                .andExpect(status().isOk());
        return data(result).path("id").asLong();
    }

    protected int stockOf(long productId) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }
}
