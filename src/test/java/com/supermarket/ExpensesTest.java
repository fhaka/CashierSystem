package com.supermarket;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExpensesTest extends IntegrationTest {

    private long category(String token, String name) throws Exception {
        for (JsonNode c : data(getJson("/expenses/categories", token))) {
            if (c.path("name").asText().equals(name)) {
                return c.path("id").asLong();
            }
        }
        throw new AssertionError("no category " + name);
    }

    /** A cashier does not see the expected cash of an open shift (blind count); the manager does. */
    private BigDecimal expectedCash(String token, long shift) throws Exception {
        return data(getJson("/shifts/" + shift + "/report", token)).path("expectedCash").decimalValue();
    }

    @Test
    void aCashExpenseLeavesTheTillOfTheOpenShift() throws Exception {
        String admin = registerSuperAdmin();
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        long electricity = category(cashier, "Energji elektrike");

        // No open shift: no cash to pay from.
        postJson("/expenses", cashier, Map.of("categoryId", electricity, "amount", "1500", "method", "CASH"))
                .andExpect(jsonPath("$.code").value("expense.shiftRequired"));
        long shift = openShift(cashier, "5000");
        JsonNode expense = data(postJson("/expenses", cashier, Map.of("categoryId", electricity, "amount", "1500", "method", "CASH",
                "paidTo", "OSHEE", "note", "Fatura e shtatorit")).andExpect(status().isOk()));
        assertThat(expense.path("shiftId").asLong()).isEqualTo(shift);
        assertThat(expense.path("paidOn").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(expectedCash(admin, shift)).isEqualByComparingTo("3500.00");
        assertThat(jdbc.queryForObject("SELECT reason FROM cash_movements WHERE shift_id = ?", String.class, shift))
                .startsWith("Shpenzim / expense: Energji elektrike - OSHEE");

        // A cashier cannot record bank payments, nor see or cancel expenses.
        postJson("/expenses", cashier, Map.of("categoryId", electricity, "amount", "100", "method", "BANK"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("expense.cashOnly"));
        getJson("/expenses", cashier).andExpect(status().isForbidden());
        postJson("/expenses/" + expense.path("id").asLong() + "/cancel", cashier, null).andExpect(status().isForbidden());

        // A manager cancels it while the shift is open: the cash goes back into that till.
        postJson("/expenses/" + expense.path("id").asLong() + "/cancel", admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancelled").value(true));
        assertThat(expectedCash(admin, shift)).isEqualByComparingTo("5000.00");
        postJson("/expenses/" + expense.path("id").asLong() + "/cancel", admin, null)
                .andExpect(jsonPath("$.code").value("expense.alreadyCancelled"));

        // Once the shift is closed, a cash expense of it can no longer be cancelled.
        JsonNode second = data(postJson("/expenses", cashier, Map.of("categoryId", electricity, "amount", "200", "method", "CASH")));
        postJson("/shifts/" + shift + "/close", cashier, Map.of("closingCash", "4800")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT difference FROM shifts WHERE id = ?", BigDecimal.class, shift)).isEqualByComparingTo("0");
        postJson("/expenses/" + second.path("id").asLong() + "/cancel", admin, null)
                .andExpect(jsonPath("$.code").value("expense.shiftClosed"));
    }

    @Test
    void managersRecordBankExpensesAndSeeTotalsByCategory() throws Exception {
        String admin = registerSuperAdmin();
        long rent = category(admin, "Qira");
        long salaries = category(admin, "Paga");
        LocalDate first = LocalDate.now().withDayOfMonth(1);

        postJson("/expenses", admin, Map.of("categoryId", rent, "amount", "40000", "method", "BANK", "paidOn", first.toString(),
                "paidTo", "Pronari")).andExpect(status().isOk());
        postJson("/expenses", admin, Map.of("categoryId", salaries, "amount", "30000", "method", "BANK")).andExpect(status().isOk());
        postJson("/expenses", admin, Map.of("categoryId", salaries, "amount", "5000", "method", "CARD")).andExpect(status().isOk());
        openShift(admin, "1000");
        postJson("/expenses", admin, Map.of("categoryId", rent, "amount", "250.5", "method", "CASH")).andExpect(status().isOk());
        // Last month: outside this month's list.
        postJson("/expenses", admin, Map.of("categoryId", rent, "amount", "40000", "method", "BANK",
                "paidOn", first.minusMonths(1).toString())).andExpect(status().isOk());

        postJson("/expenses", admin, Map.of("categoryId", rent, "amount", "1", "method", "BANK", "paidOn", LocalDate.now().plusDays(1).toString()))
                .andExpect(jsonPath("$.code").value("expense.futureDate"));
        postJson("/expenses", admin, Map.of("categoryId", rent, "amount", "0", "method", "BANK"))
                .andExpect(jsonPath("$.code").value("payment.amountPositive"));
        postJson("/expenses", admin, Map.of("categoryId", rent, "amount", "1", "method", "CHEQUE"))
                .andExpect(jsonPath("$.code").value("expense.invalidMethod"));

        JsonNode month = data(getJson("/expenses", admin).andExpect(status().isOk()));
        assertThat(month.path("expenses")).hasSize(4);
        assertThat(month.path("total").decimalValue()).isEqualByComparingTo("75250.50");
        assertThat(month.path("bank").decimalValue()).isEqualByComparingTo("70000");
        assertThat(month.path("card").decimalValue()).isEqualByComparingTo("5000");
        assertThat(month.path("cash").decimalValue()).isEqualByComparingTo("250.50");
        assertThat(month.path("byCategory").get(0).path("category").asText()).isEqualTo("Qira");
        assertThat(month.path("byCategory").get(0).path("amount").decimalValue()).isEqualByComparingTo("40250.50");
        assertThat(month.path("byCategory").get(1).path("count").asInt()).isEqualTo(2);
        assertThat(data(getJson("/expenses?categoryId=" + salaries, admin)).path("total").decimalValue()).isEqualByComparingTo("35000");

        // The journal lists each expense once (the till movement of the cash one is not repeated).
        JsonNode journal = data(getJson("/journal", admin));
        assertThat(journal.path("entries").findValuesAsText("type")).doesNotContain("CASH_OUT").contains("EXPENSE");

        byte[] xlsx = getJson("/expenses/export.xlsx", admin).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(4);
        }
    }

    @Test
    void categoriesCanBeAddedRenamedAndRetired() throws Exception {
        String admin = registerSuperAdmin();
        long id = data(postJson("/expenses/categories", admin, Map.of("name", "Reklama")).andExpect(status().isOk())).path("id").asLong();
        postJson("/expenses/categories", admin, Map.of("name", "reklama")).andExpect(jsonPath("$.code").value("expense.categoryExists"));
        postJson("/expenses/categories", admin, Map.of("name", " ")).andExpect(jsonPath("$.code").value("expense.categoryNameRequired"));
        putJson("/expenses/categories/" + id, admin, Map.of("name", "Reklama dhe fletëpalosje", "active", false)).andExpect(status().isOk());

        postJson("/expenses", admin, Map.of("categoryId", id, "amount", "100", "method", "BANK"))
                .andExpect(jsonPath("$.code").value("expense.categoryInactive"));
        assertThat(data(getJson("/expenses/categories", admin)).findValuesAsText("name")).doesNotContain("Reklama dhe fletëpalosje");
        assertThat(data(getJson("/expenses/categories?activeOnly=false", admin)).findValuesAsText("name")).contains("Reklama dhe fletëpalosje");
        String cashier = createUserAndLogin(admin, "arka", "CASHIER");
        postJson("/expenses/categories", cashier, Map.of("name", "X")).andExpect(status().isForbidden());
    }
}
