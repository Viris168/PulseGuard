package com.viris.PulseGuard.monitor;

import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.common.net.SafeUrlValidator;
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

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PRD 2.1 request options through the real API: several status codes, headers and a body.
 * The point that matters most: a secret header's value goes in, is used, and never comes out.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MonitorRequestOptionsIntegrationTest {

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

        @Bean
        @Primary
        SafeUrlValidator urlValidator() {
            return new SafeUrlValidator(host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")});
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    UserRepository users;
    @Autowired
    MonitorRepository monitors;

    private String alice;

    private static final String FULL = """
            {"name":"GraphQL","url":"https://api.example.com/graphql","method":"POST",
             "expectedStatuses":[200,204],"intervalSeconds":300,"timeoutMs":5000,
             "headers":[{"name":"Authorization","value":"Bearer s3cret"},{"name":"X-Env","value":"prod"}],
             "requestBody":"{\\"query\\":\\"{ health }\\"}"}
            """;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll();
        String body = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test","email":"alice@example.com","password":"Sup3rSecret!"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        alice = "Bearer " + objectMapper.readTree(body).get("token").asString();
    }

    private ResultActions create(String json) throws Exception {
        return mockMvc.perform(post("/api/monitors").header(HttpHeaders.AUTHORIZATION, alice)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long createFull() throws Exception {
        String json = create(FULL).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asLong();
    }

    private ResultActions update(long id, String json) throws Exception {
        return mockMvc.perform(put("/api/monitors/" + id).header(HttpHeaders.AUTHORIZATION, alice)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void savesStatusesHeadersAndBodyButNeverReturnsASecretValue() throws Exception {
        String json = create(FULL)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expectedStatuses[0]").value(200))
                .andExpect(jsonPath("$.expectedStatuses[1]").value(204))
                .andExpect(jsonPath("$.expectedStatus").value(200))
                .andExpect(jsonPath("$.headers[0].name").value("Authorization"))
                .andExpect(jsonPath("$.headers[0].secret").value(true))
                .andExpect(jsonPath("$.headers[0].value").doesNotExist())
                .andExpect(jsonPath("$.headers[1].value").value("prod"))
                .andExpect(jsonPath("$.requestBody").value("{\"query\":\"{ health }\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("s3cret");

        long id = objectMapper.readTree(json).get("id").asLong();
        String fetched = mockMvc.perform(get("/api/monitors/" + id).header(HttpHeaders.AUTHORIZATION, alice))
                .andReturn().getResponse().getContentAsString();
        String listed = mockMvc.perform(get("/api/monitors").header(HttpHeaders.AUTHORIZATION, alice))
                .andReturn().getResponse().getContentAsString();
        assertThat(fetched).doesNotContain("s3cret");
        assertThat(listed).doesNotContain("s3cret");

        // Stored in full, because the check has to send it.
        assertThat(monitors.findWithHeadersById(id).orElseThrow().getHeaders().getFirst().getValue()).isEqualTo("Bearer s3cret");
    }

    @Test
    void anEditThatLeavesASecretBlankKeepsTheSavedValue() throws Exception {
        long id = createFull();

        update(id, """
                {"name":"GraphQL v2","url":"https://api.example.com/graphql","method":"POST",
                 "expectedStatuses":[200],"intervalSeconds":300,"timeoutMs":5000,
                 "headers":[{"name":"authorization","value":null},{"name":"X-Env","value":"staging"}],
                 "requestBody":"{}"}
                """).andExpect(status().isOk());

        Monitor saved = monitors.findWithHeadersById(id).orElseThrow();
        assertThat(saved.getHeaders()).extracting(MonitorHeader::getValue).containsExactly("Bearer s3cret", "staging");
        assertThat(saved.getExpectedStatuses()).containsExactly(200);
    }

    @Test
    void aBlankValueWithNothingSavedIsAnError() throws Exception {
        create("""
                {"name":"API","url":"https://api.example.com","method":"GET","expectedStatus":200,
                 "intervalSeconds":300,"timeoutMs":5000,"headers":[{"name":"X-Api-Key","value":null}]}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.headers").value("Enter a value for the X-Api-Key header"));
    }

    @Test
    void removingAHeaderRemovesIt() throws Exception {
        long id = createFull();

        update(id, """
                {"name":"GraphQL","url":"https://api.example.com/graphql","method":"GET",
                 "expectedStatuses":[200],"intervalSeconds":300,"timeoutMs":5000,"headers":[]}
                """).andExpect(status().isOk())
                .andExpect(jsonPath("$.headers.length()").value(0))
                .andExpect(jsonPath("$.requestBody").doesNotExist());
    }

    @Test
    void theSingleStatusFieldStillWorksForOlderClients() throws Exception {
        create("""
                {"name":"API","url":"https://api.example.com","method":"GET","expectedStatus":204,
                 "intervalSeconds":300,"timeoutMs":5000}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expectedStatuses[0]").value(204))
                .andExpect(jsonPath("$.headers.length()").value(0));
    }

    private ResultActions createWith(String extra) throws Exception {
        return create("""
                {"name":"API","url":"https://api.example.com","method":"GET","intervalSeconds":300,
                 "timeoutMs":5000,%s}
                """.formatted(extra));
    }

    @Test
    void rejectsBadStatusLists() throws Exception {
        createWith("\"expectedStatuses\":[]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.expectedStatuses").value("Expected status code is required"));
        createWith("\"expectedStatuses\":[200,99]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.expectedStatuses").value("Status codes must be between 100 and 599"));
        createWith("\"expectedStatuses\":[200,200]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.expectedStatuses").value("Each status code can appear only once"));
    }

    @Test
    void refusesHeadersThatCouldRedirectOrBreakTheRequest() throws Exception {
        createWith("\"expectedStatus\":200,\"headers\":[{\"name\":\"Host\",\"value\":\"169.254.169.254\"}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.headers").value("The Host header is set by PulseGuard and can't be changed"));
        createWith("\"expectedStatus\":200,\"headers\":[{\"name\":\"X-Evil\",\"value\":\"a\\r\\nX-Injected: 1\"}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.headers").value("Header values can't contain line breaks"));
        createWith("\"expectedStatus\":200,\"headers\":[{\"name\":\"Bad Name\",\"value\":\"x\"}]")
                .andExpect(status().isBadRequest());
        createWith("\"expectedStatus\":200,\"headers\":[{\"name\":\"X-A\",\"value\":\"1\"},{\"name\":\"x-a\",\"value\":\"2\"}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.headers").value("The x-a header appears twice"));
    }

    @Test
    void onlyPostAndPutCanSendABody() throws Exception {
        createWith("\"expectedStatus\":200,\"requestBody\":\"{}\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.requestBody").value("Only POST and PUT checks can send a body"));
    }

    private String apiKey() throws Exception {
        String created = mockMvc.perform(post("/api/api-keys").header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"CI\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(created).get("secret").asString();
    }

    @Test
    void anApiKeyNeverReadsTheBodyAndCannotEraseItByAccident() throws Exception {
        long id = createFull();
        String key = apiKey();

        mockMvc.perform(get("/api/monitors/" + id).header(HttpHeaders.AUTHORIZATION, key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestBody").doesNotExist())
                .andExpect(jsonPath("$.requestBodyHidden").value(true));
        // The signed-in owner still sees it, to edit it.
        mockMvc.perform(get("/api/monitors/" + id).header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(jsonPath("$.requestBody").value("{\"query\":\"{ health }\"}"))
                .andExpect(jsonPath("$.requestBodyHidden").value(false));

        // A script writes back what it read (no body): the saved body survives.
        mockMvc.perform(put("/api/monitors/" + id).header(HttpHeaders.AUTHORIZATION, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"GraphQL renamed","url":"https://api.example.com/graphql","method":"POST",
                                 "expectedStatuses":[200],"intervalSeconds":300,"timeoutMs":5000,
                                 "headers":[{"name":"Authorization","value":null}]}
                                """))
                .andExpect(status().isOk());
        Monitor saved = monitors.findWithHeadersById(id).orElseThrow();
        assertThat(saved.getRequestBody()).isEqualTo("{\"query\":\"{ health }\"}");
        assertThat(saved.getHeaders().getFirst().getValue()).isEqualTo("Bearer s3cret");
    }

    @Test
    void aBlankBodyClearsIt() throws Exception {
        long id = createFull();

        update(id, """
                {"name":"GraphQL","url":"https://api.example.com/graphql","method":"POST",
                 "expectedStatuses":[200],"intervalSeconds":300,"timeoutMs":5000,"requestBody":""}
                """).andExpect(status().isOk());

        assertThat(monitors.findById(id).orElseThrow().getRequestBody()).isNull();
    }
}
