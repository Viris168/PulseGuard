package com.viris.PulseGuard.statuspage;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.check.Check;
import com.viris.PulseGuard.check.CheckRepository;
import com.viris.PulseGuard.enumeration.CheckResult;
import com.viris.PulseGuard.enumeration.IncidentStatus;
import com.viris.PulseGuard.enumeration.MonitorState;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.incident.Incident;
import com.viris.PulseGuard.incident.IncidentRepository;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Both sides of the status page through the real filter chain: the owner's editor API with
 * two tenants, and the public endpoint with no token at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatusPageApiIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @TestConfiguration
    static class TestBackends {
        @Bean
        @Primary
        TokenDenylist denylist() {
            return new InMemoryTokenDenylist();
        }

        @Bean
        @Primary
        LoginRateLimiter rateLimiter() {
            return new InMemoryLoginRateLimiter(5, 20);
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    UserRepository users;
    @Autowired
    MonitorRepository monitors;
    @Autowired
    CheckRepository checks;
    @Autowired
    IncidentRepository incidents;
    @Autowired
    StatusPageRepository statusPages;
    @Autowired
    ObjectMapper objectMapper;

    private String alice;
    private String bob;
    private Monitor api;
    private Monitor website;
    private Monitor bobsMonitor;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll(); // monitors, checks, incidents and pages follow by ON DELETE CASCADE
        alice = "Bearer " + register("alice@example.com");
        bob = "Bearer " + register("bob@example.com");
        api = monitor("alice@example.com", "internal-api-prod", "https://internal.example.com/health?key=s3cret");
        website = monitor("alice@example.com", "www", "https://example.com");
        bobsMonitor = monitor("bob@example.com", "bob-api", "https://bob.example.com");
    }

    private String register(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test","email":"%s","password":"Sup3rSecret!"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asString();
    }

    private Monitor monitor(String ownerEmail, String name, String url) {
        Monitor m = new Monitor();
        m.setUser(users.findByEmail(ownerEmail).orElseThrow());
        m.setName(name);
        m.setUrl(url);
        return monitors.save(m);
    }

    private ResultActions save(String auth, String json) throws Exception {
        return mockMvc.perform(put("/api/status-page")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private String page(String slug, boolean published, Monitor... shown) {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < shown.length; i++) {
            if (i > 0) {
                list.append(',');
            }
            list.append("{\"monitorId\":%d,\"displayName\":\"Component %d\"}".formatted(shown[i].getId(), i + 1));
        }
        return """
                {"slug":"%s","title":"Acme Status","description":"","published":%s,"monitors":[%s]}
                """.formatted(slug, published, list);
    }

    private ResultActions publicPage(String slug) throws Exception {
        return mockMvc.perform(get("/api/status/" + slug));
    }

    private void setPlan(String email, Plan plan) {
        User user = users.findByEmail(email).orElseThrow();
        user.setPlan(plan);
        users.save(user);
    }

    // ── Owner side ──

    @Test
    void thereIsNoPageBeforeTheFirstSave() throws Exception {
        mockMvc.perform(get("/api/status-page").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isNoContent());
    }

    @Test
    void theEditorNeedsALogin() throws Exception {
        mockMvc.perform(get("/api/status-page")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/status-page").contentType(MediaType.APPLICATION_JSON)
                        .content(page("acme", true, api)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void savesAndReturnsThePageNormalisedAndInOrder() throws Exception {
        save(alice, """
                {"slug":"  Acme ","title":"  Acme Status ","description":" Live status. ","published":true,
                 "monitors":[{"monitorId":%d,"displayName":" Website "},{"monitorId":%d,"displayName":"API"}]}
                """.formatted(website.getId(), api.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("acme"))
                .andExpect(jsonPath("$.title").value("Acme Status"))
                .andExpect(jsonPath("$.description").value("Live status."))
                .andExpect(jsonPath("$.monitors[0].monitorId").value(website.getId()))
                .andExpect(jsonPath("$.monitors[0].displayName").value("Website"))
                .andExpect(jsonPath("$.monitors[1].monitorId").value(api.getId()));

        mockMvc.perform(get("/api/status-page").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monitors.length()").value(2))
                .andExpect(jsonPath("$.monitors[0].displayName").value("Website"));
    }

    @Test
    void reorderingAndRemovingMonitorsReplacesTheList() throws Exception {
        save(alice, page("acme", true, api, website)).andExpect(status().isOk());

        save(alice, page("acme", true, website)).andExpect(status().isOk())
                .andExpect(jsonPath("$.monitors.length()").value(1))
                .andExpect(jsonPath("$.monitors[0].monitorId").value(website.getId()));
        save(alice, page("acme", true, api, website)).andExpect(status().isOk());
        save(alice, page("acme", true, website, api)).andExpect(status().isOk())
                .andExpect(jsonPath("$.monitors[0].monitorId").value(website.getId()));

        assertThat(statusPages.count()).isEqualTo(1);
    }

    @Test
    void rejectsABadOrReservedAddress() throws Exception {
        save(alice, page("a", true, api)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.slug").value("Use 3–40 lowercase letters, numbers or dashes"));
        save(alice, page("-acme", true, api)).andExpect(status().isBadRequest());
        save(alice, page("admin", true, api)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.slug").value("That address is reserved"));
    }

    @Test
    void rejectsABlankTitleAndDisplayName() throws Exception {
        save(alice, """
                {"slug":"acme","title":" ","description":"","published":true,
                 "monitors":[{"monitorId":%d,"displayName":" "}]}
                """.formatted(api.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").value("Title is required"));

        save(alice, """
                {"slug":"acme","title":"Acme","description":"","published":true,
                 "monitors":[{"monitorId":%d,"displayName":" "}]}
                """.formatted(api.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.monitors").value("Every monitor needs a display name"));
    }

    @Test
    void rejectsTheSameMonitorTwice() throws Exception {
        save(alice, page("acme", true, api, api)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.monitors").value("Each monitor can appear only once"));
    }

    @Test
    void anotherTenantsMonitorIsNotFound() throws Exception {
        save(alice, page("acme", true, api, bobsMonitor)).andExpect(status().isNotFound());

        assertThat(statusPages.count()).isZero();
    }

    @Test
    void anAddressAnotherAccountUsesIsAConflict() throws Exception {
        save(bob, page("acme", true, bobsMonitor)).andExpect(status().isOk());

        save(alice, page("acme", true, api)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.fieldErrors.slug").value("That address is already taken"));
        // Keeping your own address on a later save is not a conflict.
        save(bob, page("acme", false, bobsMonitor)).andExpect(status().isOk());
    }

    // ── Public side ──

    @Test
    void thePublicPageNeedsNoLoginAndShowsOnlyWhatWasPublished() throws Exception {
        save(alice, page("acme", true, api, website)).andExpect(status().isOk());

        String body = publicPage("acme")
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                .andExpect(jsonPath("$.title").value("Acme Status"))
                .andExpect(jsonPath("$.overall").value("OPERATIONAL"))
                .andExpect(jsonPath("$.components.length()").value(2))
                .andExpect(jsonPath("$.components[0].name").value("Component 1"))
                .andExpect(jsonPath("$.components[0].status").value("OPERATIONAL"))
                .andReturn().getResponse().getContentAsString();

        // Internal names, URLs (with their secrets) and ids stay private.
        assertThat(body).doesNotContain("internal-api-prod", "internal.example.com", "s3cret", "monitorId");
    }

    @Test
    void theAddressIsCaseInsensitive() throws Exception {
        save(alice, page("acme", true, api)).andExpect(status().isOk());

        publicPage("ACME").andExpect(status().isOk());
    }

    @Test
    void unpublishedAndUnknownPagesAreNotFound() throws Exception {
        save(alice, page("acme", false, api)).andExpect(status().isOk());

        publicPage("acme").andExpect(status().isNotFound());
        publicPage("nope").andExpect(status().isNotFound());
    }

    @Test
    void renamingTheAddressRetiresTheOldOne() throws Exception {
        save(alice, page("acme", true, api)).andExpect(status().isOk());
        save(alice, page("acme-corp", true, api)).andExpect(status().isOk());

        publicPage("acme").andExpect(status().isNotFound());
        publicPage("acme-corp").andExpect(status().isOk());
    }

    @Test
    void componentStatusFollowsTheMonitor() throws Exception {
        save(alice, page("acme", true, api, website)).andExpect(status().isOk());
        api.setState(MonitorState.DOWN);
        monitors.save(api);
        website.setActive(false);
        monitors.save(website);

        publicPage("acme")
                .andExpect(jsonPath("$.components[0].status").value("OUTAGE"))
                .andExpect(jsonPath("$.components[1].status").value("PAUSED"))
                // The paused one says nothing, so the only live component being down is a major outage.
                .andExpect(jsonPath("$.overall").value("MAJOR_OUTAGE"));
    }

    @Test
    void uptimeComesFromChecksAndHistoryFollowsThePlan() throws Exception {
        save(alice, page("acme", true, api)).andExpect(status().isOk());
        Instant recent = Instant.now().minus(Duration.ofMinutes(2));
        for (CheckResult result : new CheckResult[]{CheckResult.UP, CheckResult.UP, CheckResult.UP, CheckResult.DOWN}) {
            Check check = new Check();
            check.setMonitor(api);
            check.setResult(result);
            check.setResponseTimeMs(100);
            check.setCheckedAt(recent);
            checks.save(check);
        }

        publicPage("acme")
                .andExpect(jsonPath("$.historyDays").value(7))
                .andExpect(jsonPath("$.components[0].days.length()").value(7))
                .andExpect(jsonPath("$.components[0].uptimePct").value(75.0));

        setPlan("alice@example.com", Plan.PRO);
        publicPage("acme")
                .andExpect(jsonPath("$.historyDays").value(90))
                .andExpect(jsonPath("$.components[0].days.length()").value(90));
    }

    @Test
    void recentIncidentsAreListedWithoutTheirCause() throws Exception {
        save(alice, page("acme", true, api)).andExpect(status().isOk());
        Incident incident = new Incident();
        incident.setMonitor(api);
        incident.setStatus(IncidentStatus.OPEN);
        incident.setCause("Connection refused to 10.0.0.12:8443");
        incident.setStartedAt(Instant.now().minus(Duration.ofMinutes(30)));
        incidents.save(incident);

        String body = publicPage("acme")
                .andExpect(jsonPath("$.incidents.length()").value(1))
                .andExpect(jsonPath("$.incidents[0].componentName").value("Component 1"))
                .andExpect(jsonPath("$.incidents[0].status").value("OPEN"))
                .andExpect(jsonPath("$.components[0].days[6].downtimeSeconds").isNumber())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Connection refused", "10.0.0.12");
    }

    @Test
    void aDeletedMonitorDropsOffThePage() throws Exception {
        save(alice, page("acme", true, api, website)).andExpect(status().isOk());

        monitors.delete(api);

        publicPage("acme").andExpect(jsonPath("$.components.length()").value(1))
                .andExpect(jsonPath("$.components[0].name").value("Component 2"));
        mockMvc.perform(get("/api/status-page").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(jsonPath("$.monitors.length()").value(1));
    }
}
