package com.supermarket;

import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
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
                .andExpect(jsonPath("$.data.version").value(matchesPattern("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?")))
                .andExpect(jsonPath("$.data.databaseVersion").value(matchesPattern("v\\d+")))
                .andExpect(jsonPath("$.data.backupFolder").isNotEmpty());
    }
}
