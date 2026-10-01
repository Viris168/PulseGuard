package com.viris.PulseGuard.auth.account;

import com.viris.PulseGuard.TestDatabase;
import com.viris.PulseGuard.auth.User;
import com.viris.PulseGuard.auth.UserRepository;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import com.viris.PulseGuard.billing.Subscription;
import com.viris.PulseGuard.billing.repository.SubscriptionRepository;
import com.viris.PulseGuard.billing.stripe.StripeGateway;
import com.viris.PulseGuard.common.exception.PaymentProviderException;
import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.enumeration.Plan;
import com.viris.PulseGuard.enumeration.SubscriptionStatus;
import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.monitor.MonitorRepository;
import com.viris.PulseGuard.notification.NotificationChannel;
import com.viris.PulseGuard.notification.repository.NotificationChannelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Email verification, email change and account deletion through the real API. Links are
 * taken from the captured emails and used as a person would; Stripe and SMTP are mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountManagementIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestDatabase.IMAGE);

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
        EmailSendThrottle emailThrottle() {
            return (purpose, email, ip) -> true;
        }
    }

    private static final Pattern TOKEN = Pattern.compile("\\?token=([A-Za-z0-9_-]+)");

    @MockitoBean
    JavaMailSender mailSender;
    @MockitoBean
    StripeGateway stripe;

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    UserRepository users;
    @Autowired
    NotificationChannelRepository channels;
    @Autowired
    MonitorRepository monitors;
    @Autowired
    SubscriptionRepository subscriptions;
    @Autowired
    com.viris.PulseGuard.billing.repository.DeletedStripeCustomerRepository deletedCustomers;

    private String alice;
    private String verifyToken;

    @BeforeEach
    void setUp() throws Exception {
        users.deleteAll();
        String body = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Alice","email":"alice@example.com","password":"Sup3rSecret!"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        alice = "Bearer " + objectMapper.readTree(body).get("token").asString();
        verifyToken = tokenFrom(mailTo("alice@example.com", "Verify your email for PulseGuard"));
    }

    /**
     * Waits for the async email with this subject to this address; returns its text. Waits for that
     * exact email, not just any email: several are sent in the background and arrive in any order.
     */
    private String mailTo(String to, String subject) {
        Predicate<SimpleMailMessage> wanted = m -> subject.equals(m.getSubject()) && Arrays.asList(m.getTo()).contains(to);
        try {
            verify(mailSender, timeout(5000).atLeast(1)).send(ArgumentMatchers.<SimpleMailMessage>argThat(wanted::test));
        } catch (AssertionError e) {
            throw new AssertionError("No '" + subject + "' email to " + to, e);
        }
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, atLeast(1)).send(mail.capture());
        return mail.getAllValues().stream().filter(wanted).reduce((a, b) -> b).orElseThrow().getText();
    }

    private static String tokenFrom(String text) {
        Matcher m = TOKEN.matcher(text);
        assertThat(m.find()).as("link in email").isTrue();
        return m.group(1);
    }

    private ResultActions postJson(String path, String auth, Map<String, String> body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        return mockMvc.perform(auth == null ? request : request.header(HttpHeaders.AUTHORIZATION, auth));
    }

    private ResultActions me() throws Exception {
        return mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, alice));
    }

    private long defaultChannelId() {
        User user = users.findByEmail("alice@example.com").orElseThrow();
        return channels.findAllByUserId(user.getId()).getFirst().getId();
    }

    // ── Verification ──

    @Test
    void emailAlertsWaitUntilTheAddressIsVerified() throws Exception {
        me().andExpect(jsonPath("$.emailVerified").value(false));
        long channel = defaultChannelId();
        mockMvc.perform(post("/api/channels/" + channel + "/test").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Verify your account's email first. Email alerts start once it's confirmed."));

        postJson("/api/auth/verify-email", null, Map.of("token", verifyToken)).andExpect(status().isNoContent());

        me().andExpect(jsonPath("$.emailVerified").value(true));
        mockMvc.perform(post("/api/channels/" + channel + "/test").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isNoContent());
    }

    @Test
    void aVerificationLinkWorksOnce() throws Exception {
        postJson("/api/auth/verify-email", null, Map.of("token", verifyToken)).andExpect(status().isNoContent());
        postJson("/api/auth/verify-email", null, Map.of("token", verifyToken)).andExpect(status().isBadRequest());
    }

    @Test
    void resendSendsAFreshLinkAndRetiresTheOldOne() throws Exception {
        clearInvocations(mailSender);
        mockMvc.perform(post("/api/auth/verify-email/resend").header(HttpHeaders.AUTHORIZATION, alice))
                .andExpect(status().isAccepted());
        String fresh = tokenFrom(mailTo("alice@example.com", "Verify your email for PulseGuard"));

        postJson("/api/auth/verify-email", null, Map.of("token", verifyToken)).andExpect(status().isBadRequest());
        postJson("/api/auth/verify-email", null, Map.of("token", fresh)).andExpect(status().isNoContent());
    }

    // ── Email change ──

    @Test
    void changingEmailNeedsTheNewAddressToConfirmAndTellsTheOldOne() throws Exception {
        postJson("/api/auth/verify-email", null, Map.of("token", verifyToken));
        User user = users.findByEmail("alice@example.com").orElseThrow();
        user.setStripeCustomerId("cus_123");
        users.save(user);
        clearInvocations(mailSender);

        postJson("/api/auth/email", alice, Map.of("newEmail", "Alice.New@Example.com", "currentPassword", "Sup3rSecret!"))
                .andExpect(status().isAccepted());
        String confirm = tokenFrom(mailTo("alice.new@example.com", "Confirm your new email for PulseGuard"));
        assertThat(mailTo("alice@example.com", "Your PulseGuard email is being changed")).contains("alice.new@example.com");

        me().andExpect(jsonPath("$.email").value("alice@example.com")); // nothing changes yet

        postJson("/api/auth/confirm-email", null, Map.of("token", confirm)).andExpect(status().isNoContent());

        me().andExpect(jsonPath("$.email").value("alice.new@example.com"))
                .andExpect(jsonPath("$.emailVerified").value(true));
        NotificationChannel channel = channels.findById(defaultChannelId(user.getId())).orElseThrow();
        assertThat(channel.getTarget()).isEqualTo("alice.new@example.com");
        postJson("/api/auth/login", null, Map.of("email", "alice.new@example.com", "password", "Sup3rSecret!"))
                .andExpect(status().isOk());
        verify(stripe, timeout(5000)).updateCustomerEmail("cus_123", "alice.new@example.com");
    }

    private long defaultChannelId(Long userId) {
        return channels.findAllByUserId(userId).stream()
                .filter(c -> c.getType() == ChannelType.EMAIL).findFirst().orElseThrow().getId();
    }

    @Test
    void aWrongPasswordIsAFieldErrorNotASignOut() throws Exception {
        postJson("/api/auth/email", alice, Map.of("newEmail", "new@example.com", "currentPassword", "wrong-password"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.currentPassword").value("Current password is incorrect"));
        me().andExpect(status().isOk());
    }

    @Test
    void anAddressAnotherAccountUsesIsRefused() throws Exception {
        postJson("/api/auth/register", null, Map.of("name", "Bob", "email", "bob@example.com", "password", "Sup3rSecret!"))
                .andExpect(status().isCreated());

        postJson("/api/auth/email", alice, Map.of("newEmail", "bob@example.com", "currentPassword", "Sup3rSecret!"))
                .andExpect(status().isConflict());
    }

    @Test
    void anApiKeyCannotChangeTheEmailOrDeleteTheAccount() throws Exception {
        String created = mockMvc.perform(post("/api/api-keys").header(HttpHeaders.AUTHORIZATION, alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"CI\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String key = "Bearer " + objectMapper.readTree(created).get("secret").asString();

        postJson("/api/auth/email", key, Map.of("newEmail", "evil@example.com", "currentPassword", "Sup3rSecret!"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/auth/me").header(HttpHeaders.AUTHORIZATION, key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"Sup3rSecret!\"}"))
                .andExpect(status().isForbidden());
    }

    // ── Deletion ──

    private ResultActions deleteAccount(String password) throws Exception {
        return mockMvc.perform(delete("/api/auth/me").header(HttpHeaders.AUTHORIZATION, alice)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("currentPassword", password))));
    }

    private User subscribe() {
        User user = users.findByEmail("alice@example.com").orElseThrow();
        user.setPlan(Plan.PRO);
        user.setStripeCustomerId("cus_alice");
        users.save(user);
        Subscription sub = new Subscription();
        sub.setUser(user);
        sub.setStripeSubscriptionId("sub_123");
        sub.setPlan(Plan.PRO);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        subscriptions.save(sub);
        Monitor monitor = new Monitor();
        monitor.setUser(user);
        monitor.setName("API");
        monitor.setUrl("https://example.com");
        monitors.save(monitor);
        return user;
    }

    @Test
    void deletingCancelsTheSubscriptionAndRemovesEverything() throws Exception {
        User user = subscribe();

        deleteAccount("Sup3rSecret!").andExpect(status().isNoContent());

        verify(stripe).cancelSubscription("sub_123");
        // Stripe's late "subscription deleted" event for this customer will be acknowledged.
        assertThat(deletedCustomers.existsById("cus_alice")).isTrue();
        assertThat(users.findById(user.getId())).isEmpty();
        assertThat(monitors.findAllByUserId(user.getId())).isEmpty();
        assertThat(subscriptions.findByUserId(user.getId())).isEmpty();
        me().andExpect(status().isUnauthorized());
    }

    @Test
    void ifStripeCannotCancelNothingIsDeleted() throws Exception {
        User user = subscribe();
        doThrow(new PaymentProviderException(new RuntimeException("stripe down")))
                .when(stripe).cancelSubscription(anyString());

        deleteAccount("Sup3rSecret!").andExpect(status().isBadGateway());

        assertThat(users.findById(user.getId())).isPresent();
        assertThat(monitors.findAllByUserId(user.getId())).hasSize(1);
        me().andExpect(status().isOk());
    }

    @Test
    void deletingNeedsTheCurrentPassword() throws Exception {
        deleteAccount("wrong-password").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.currentPassword").exists());

        assertThat(users.findByEmail("alice@example.com")).isPresent();
        verify(stripe, never()).cancelSubscription(any());
    }

    @Test
    void aFreeAccountIsDeletedWithoutTouchingStripe() throws Exception {
        deleteAccount("Sup3rSecret!").andExpect(status().isNoContent());

        verify(stripe, never()).cancelSubscription(any());
        assertThat(users.findByEmail("alice@example.com")).isEmpty();
    }
}
