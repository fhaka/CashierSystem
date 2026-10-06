package com.supermarket;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SystemTest extends IntegrationTest {

    @Test
    void pagesAreCheckedWithTheServerSoUpdatesShowAtOnce() throws Exception {
        for (String path : new String[] {"/index.html", "/app.js", "/styles.css", "/display.html"}) {
            getJson(path, null).andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-cache")));
        }
    }

    @Test
    void systemInfoShowsTheVersions() throws Exception {
        getJson("/system/info", null).andExpect(status().isUnauthorized());
        String admin = registerSuperAdmin();
        getJson("/system/info", admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1.0.0"))
                .andExpect(jsonPath("$.data.databaseVersion").value("v9"))
                .andExpect(jsonPath("$.data.backupFolder").isNotEmpty());
    }
}
