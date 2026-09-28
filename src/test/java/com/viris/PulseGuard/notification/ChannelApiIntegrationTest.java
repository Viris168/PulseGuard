package com.viris.PulseGuard.notification;

import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.notification.repository.NotificationChannelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.web.client.RestClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Channel CRUD through the real filter chain, with two tenants and real plan gating.
 * Only SMTP is mocked, so the test button can be checked without sending mail.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChannelApiIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    static final int TEST_BUDGET = 2;

    /** Counts per test run; reset in setUp. */
    static final AtomicInteger TESTS_SENT = new AtomicInteger();

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
        TestAlertThrottle testAlertThrottle() {
            return userId -> TESTS_SENT.incrementAndGet() <= TEST_BUDGET;
        }
    }

    @MockitoBean
    JavaMailSender mailSender;

    /** Slack replaced in-process: the real SlackSender runs, nothing reaches hooks.slack.com. */
    @TestBean(name = "slackRestClient", methodName = "mockSlack")
    RestClient slackRestClient;
    static MockRestServiceServer slack;

    static RestClient mockSlack() {
        RestClient.Builder builder = RestClient.builder();
        slack = MockRestServiceServer.bindTo(builder).build();
        return builder.build();
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    UserRepository users;
    @Autowired
    NotificationChannelRepository channels;
    @Autowired
    ObjectMapper objectMapper;

    private String alice;
    private String bob;

    private static final String SLACK_URL = "https://hooks.slack.com/services/T000/B000/secretToken123";

    @BeforeEach
    void registerTwoTenants() throws Exception {
        users.deleteAll(); // channels follow by ON DELETE CASCADE
        TESTS_SENT.set(0);
        alice = "Bearer " + register("alice@example.com");
        bob = "Bearer " + register("bob@example.com");
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

    private void setPlan(String email, Plan plan) {
        User user = users.findByEmail(email).orElseThrow();
        user.setPlan(plan);
        users.save(user);
    }

    private ResultActions create(String auth, String type, String target) throws Exception {
        return mockMvc.perform(post("/api/channels")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"type":"%s","target":"%s"}
                        """.formatted(type, target)));
    }

    private long createId(String auth, String type, String target) throws Exception {
        String json = create(auth, type, target).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asLong();
    }

    private long defaultEmailChannel(String email) {
        User user = users.findByEmail(email).orElseThrow();
        return channels.findAllByUserId(user.getId()).getFirst().getId();
    }

    private ResultActions setEnabled(String auth, long id, boolean enabled) throws Exception {
        return mockMvc.perform(patch("/api/channels/" + id)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + "}"));
    }

    @Test
    void rejectsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/channels")).andExpect(status().isUnauthorized());
    }

    @Test
    void listsTheDefaultEmailChannelCreatedAtSignUp() throws Exception {
        mockMvc.perform(get("/api/channels").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("EMAIL"))
                .andExpect(jsonPath("$[0].target").value("alice@example.com"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void createsAnEmailChannelAndNormalisesTheAddress() throws Exception {
        create(alice, "EMAIL", "  OnCall@Example.com ")
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.type").value("EMAIL"))
                .andExpect(jsonPath("$.target").value("oncall@example.com"));
    }

    @Test
    void rejectsAMalformedTargetAsAFieldError() throws Exception {
        create(alice, "EMAIL", "not-an-email")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.target").value("Enter a valid email address"));
    }

    @Test
    void rejectsADuplicateChannel() throws Exception {
        create(alice, "EMAIL", "alice@example.com").andExpect(status().isConflict());
    }

    @Test
    void freePlanCannotAddSlack() throws Exception {
        create(alice, "SLACK", SLACK_URL)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("The FREE plan does not include SLACK alerts."));
    }

    @Test
    void proPlanSlackChannelNeverEchoesTheWebhookUrl() throws Exception {
        setPlan("alice@example.com", Plan.PRO);

        String json = create(alice, "SLACK", SLACK_URL)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String listed = mockMvc.perform(get("/api/channels").header(HttpHeaders.AUTHORIZATION, alice))
                .andReturn().getResponse().getContentAsString();

        assertThat(json).doesNotContain("secretToken123").contains("hooks.slack.com");
        assertThat(listed).doesNotContain("secretToken123");
    }

    @Test
    void slackTargetMustBeASlackWebhook() throws Exception {
        setPlan("alice@example.com", Plan.PRO);

        create(alice, "SLACK", "https://evil.example.com/services/x")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.target").exists());
    }

    @Test
    void togglesAChannel() throws Exception {
        long id = defaultEmailChannel("alice@example.com");

        setEnabled(alice, id, false).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        setEnabled(alice, id, true).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void cannotReEnableAChannelThePlanNoLongerIncludes() throws Exception {
        setPlan("alice@example.com", Plan.PRO);
        long slack = createId(alice, "SLACK", SLACK_URL);
        setEnabled(alice, slack, false).andExpect(status().isOk());
        setPlan("alice@example.com", Plan.FREE);

        setEnabled(alice, slack, true).andExpect(status().isForbidden());
        // Switching off is always allowed.
        setEnabled(alice, slack, false).andExpect(status().isOk());
    }

    @Test
    void deletesAChannelButNeverTheLastOne() throws Exception {
        long extra = createId(alice, "EMAIL", "team@example.com");
        long original = defaultEmailChannel("alice@example.com");

        mockMvc.perform(delete("/api/channels/" + extra).header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/channels/" + original).header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isBadRequest());

        assertThat(channels.findById(original)).isPresent();
    }

    @Test
    void anotherTenantsChannelIsNotFound() throws Exception {
        long aliceChannel = createId(alice, "EMAIL", "team@example.com");

        setEnabled(bob, aliceChannel, false).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/channels/" + aliceChannel + "/test").header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/channels/" + aliceChannel).header(HttpHeaders.AUTHORIZATION, bob))
                .andExpect(status().isNotFound());

        assertThat(channels.findById(aliceChannel)).get()
                .extracting(NotificationChannel::isEnabled).isEqualTo(true);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendsATestEmailToTheChannel() throws Exception {
        long id = defaultEmailChannel("alice@example.com");

        mockMvc.perform(post("/api/channels/" + id + "/test").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isNoContent());

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getTo()).containsExactly("alice@example.com");
        assertThat(mail.getValue().getSubject()).contains("Test alert");
    }

    @Test
    void reportsAFailedTestSendWithoutLeakingDetails() throws Exception {
        long id = defaultEmailChannel("alice@example.com");
        doThrow(new MailSendException("smtp said no to alice@example.com"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        mockMvc.perform(post("/api/channels/" + id + "/test").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(
                        "The test alert could not be delivered. Check the target and try again."));
    }

    @Test
    void throttlesTestAlerts() throws Exception {
        long id = defaultEmailChannel("alice@example.com");

        for (int i = 0; i < TEST_BUDGET; i++) {
            mockMvc.perform(post("/api/channels/" + id + "/test").header(HttpHeaders.AUTHORIZATION, alice))
                    .andExpect(status().isNoContent());
        }
        mockMvc.perform(post("/api/channels/" + id + "/test").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void testingASlackChannelPostsATestAlertToSlack() throws Exception {
        setPlan("alice@example.com", Plan.PRO);
        long id = createId(alice, "SLACK", SLACK_URL);
        slack.reset();
        slack.expect(requestTo(SLACK_URL))
                .andExpect(MockRestRequestMatchers.jsonPath("$.text").value("🔔 Test alert from PulseGuard"))
                .andRespond(withSuccess("ok", MediaType.TEXT_PLAIN));

        mockMvc.perform(post("/api/channels/" + id + "/test").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isNoContent());

        slack.verify();
    }

    @Test
    void slackRefusingTheTestAlertIsABadGatewayWithoutTheUrl() throws Exception {
        setPlan("alice@example.com", Plan.PRO);
        long id = createId(alice, "SLACK", SLACK_URL);
        slack.reset();
        slack.expect(requestTo(SLACK_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.TEXT_PLAIN).body("no_service"));

        mockMvc.perform(post("/api/channels/" + id + "/test").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(not(containsString("secretToken123"))));
    }

    @Test
    void testingAChannelTypeWithNoSenderSaysSo() throws Exception {
        setPlan("alice@example.com", Plan.BUSINESS);
        long sms = createId(alice, "SMS", "+85512345678");

        mockMvc.perform(post("/api/channels/" + sms + "/test").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("SMS alerts can't be delivered yet."));
    }
}
