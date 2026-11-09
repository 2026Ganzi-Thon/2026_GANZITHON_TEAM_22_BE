package kr.ganzithon.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "ganzi.rate-limit.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles({"demo", "test"})
@ContextConfiguration(initializers = TestDatabaseGuard.class)
class DemoProfileIntegrationTest {
    @Autowired MockMvc mvc;

    @Test void enabledDemoServesOnlyStaticUiWithBrowserRestrictions() throws Exception {
        mvc.perform(get("/demo/index.html"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")));
        mvc.perform(get("/demo/app.js")).andExpect(status().isOk());
        mvc.perform(get("/demo/styles.css")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/demo/assets/../app.js")).andExpect(status().is4xxClientError());
    }
}
