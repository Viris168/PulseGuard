package com.viris.PulseGuard.auth.reset;

import com.viris.PulseGuard.TestDatabase;
import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.common.TestAccounts;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.account.EmailSendThrottle;
import com.viris.PulseGuard.auth.account.SecureTokens;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
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
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Forgot password" end to end: the emailed link is captured from the mocked SMTP client and
 * used exactly as a user would, against real Postgres and the real filter chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordResetIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestDatabase.IMAGE);

    static {
        POSTGRES.start();
    }

    /** Per-email counts, reset before each test; the limit matches the test properties (3). */
    static final Map<String, AtomicInteger> RESET_REQUESTS = new ConcurrentHashMap<>();

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
        EmailSendThrottle emailThrottle() {
            return (purpose, email, ip) -> RESET_REQUESTS
                    .computeIfAbsent(purpose + ":" + email, e -> new AtomicInteger()).incrementAndGet() <= 3;
        }
    }

    private static final Pattern LINK = Pattern.compile("http://localhost:5173/reset-password\\?token=([A-Za-z0-9_-]+)");

    @MockitoBean
    JavaMailSender mailSender;

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordResetTokenRepository tokens;

    private String accessToken;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll();
        RESET_REQUESTS.clear();
        String body = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Alice","email":"alice@example.com","password":"old-password-1"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        accessToken = objectMapper.readTree(body).get("token").asString();
        TestAccounts.awaitVerificationEmail(mailSender, "alice@example.com");
    }

    private ResultActions forgot(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"));
    }

    private ResultActions reset(String token, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("token", token, "newPassword", password))));
    }

    private ResultActions login(String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", "alice@example.com", "password", password))));
    }

    /** Waits for the async email and pulls the token out of its link. */
    private String emailedToken() {
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, timeout(5000)).send(mail.capture());
        assertThat(mail.getValue().getTo()).containsExactly("alice@example.com");
        assertThat(mail.getValue().getSubject()).isEqualTo("Reset your PulseGuard password");
        Matcher link = LINK.matcher(mail.getValue().getText());
        assertThat(link.find()).as("reset link in email").isTrue();
        clearInvocations(mailSender);
        return link.group(1);
    }

    @Test
    void theEmailedLinkSetsANewPasswordAndEndsEveryOldSession() throws Exception {
        Thread.sleep(1100); // iat has second precision; make the revocation cut-off strictly later.
        forgot("Alice@Example.com").andExpect(status().isAccepted()).andExpect(content().string(""));
        String token = emailedToken();

        reset(token, "brand-new-password").andExpect(status().isNoContent());

        login("brand-new-password").andExpect(status().isOk());
        login("old-password-1").andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aLinkWorksOnlyOnce() throws Exception {
        forgot("alice@example.com").andExpect(status().isAccepted());
        String token = emailedToken();

        reset(token, "brand-new-password").andExpect(status().isNoContent());
        reset(token, "another-password-2").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This reset link is invalid or has expired. Request a new one."));
        login("brand-new-password").andExpect(status().isOk());
    }

    @Test
    void anExpiredLinkIsRejected() throws Exception {
        forgot("alice@example.com").andExpect(status().isAccepted());
        String token = emailedToken();
        PasswordResetToken row = tokens.findAll().getFirst();
        row.setExpiresAt(Instant.now().minusSeconds(1));
        tokens.save(row);

        reset(token, "brand-new-password").andExpect(status().isBadRequest());
        login("old-password-1").andExpect(status().isOk());
    }

    @Test
    void aNewRequestRetiresTheOlderLink() throws Exception {
        forgot("alice@example.com").andExpect(status().isAccepted());
        String first = emailedToken();
        forgot("alice@example.com").andExpect(status().isAccepted());
        String second = emailedToken();

        reset(first, "brand-new-password").andExpect(status().isBadRequest());
        reset(second, "brand-new-password").andExpect(status().isNoContent());
    }

    @Test
    void anUnknownAddressGetsTheSameAnswerAndNoEmail() throws Exception {
        forgot("nobody@example.com").andExpect(status().isAccepted()).andExpect(content().string(""));

        verify(mailSender, after(500).never()).send(any(SimpleMailMessage.class));
        assertThat(tokens.count()).isZero();
    }

    @Test
    void aDisabledAccountGetsNoEmail() throws Exception {
        User alice = users.findByEmail("alice@example.com").orElseThrow();
        alice.setEnabled(false);
        users.save(alice);

        forgot("alice@example.com").andExpect(status().isAccepted());

        verify(mailSender, after(500).never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void requestsAreRateLimitedPerAddress() throws Exception {
        for (int i = 0; i < 3; i++) {
            forgot("alice@example.com").andExpect(status().isAccepted());
        }
        forgot("alice@example.com").andExpect(status().isTooManyRequests());
    }

    @Test
    void anUnknownTokenIsRejectedAndTheNewPasswordIsValidated() throws Exception {
        reset("not-a-real-token", "brand-new-password").andExpect(status().isBadRequest());
        reset("whatever", "short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newPassword").value("Password must be between 8 and 72 characters"));
    }

    @Test
    void onlyTheHashOfTheTokenIsStored() throws Exception {
        forgot("alice@example.com").andExpect(status().isAccepted());
        String token = emailedToken();

        PasswordResetToken row = tokens.findAll().getFirst();
        assertThat(row.getTokenHash()).isEqualTo(SecureTokens.hash(token)).isNotEqualTo(token);
    }
}
