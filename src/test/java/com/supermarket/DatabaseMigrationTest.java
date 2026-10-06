package com.supermarket;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseMigrationTest extends IntegrationTest {

    @Test
    void migrationsBuildTheSchemaTheEntitiesExpect() {
        // The context only starts if Hibernate validated every entity against the migrated tables.
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"success\" = TRUE", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(2);
    }

    @Test
    void newShopStartsWithAlbanianCategoriesAndNoDemoProducts() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM categories", Integer.class)).isEqualTo(13);
        assertThat(jdbc.queryForObject("SELECT name FROM categories WHERE id = 1", String.class)).isEqualTo("Bulmetore");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM products", Integer.class)).isZero();
    }
}
