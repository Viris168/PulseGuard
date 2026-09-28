package com.viris.PulseGuard.auth;

import com.viris.PulseGuard.apikey.ApiKeyAuthenticationFilter;
import com.viris.PulseGuard.apikey.ApiKeyAuthenticationToken;
import com.viris.PulseGuard.auth.jwt.JwtAuthenticationFilter;
import com.viris.PulseGuard.common.exception.RestAuthenticationEntryPoint;
import com.viris.PulseGuard.auth.jwt.JwtProperties;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /**
     * Signed in with a session (JWT), not an API key. Anonymous callers are denied too, and
     * Spring answers them with a 401 rather than this rule's 403.
     */
    static final AuthorizationManager<RequestAuthorizationContext> SESSION_ONLY = (authentication, context) -> {
        Authentication auth = authentication.get();
        return new AuthorizationDecision(auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken)
                && !(auth instanceof ApiKeyAuthenticationToken));
    };

    static boolean isFrontendRequest(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return HttpMethod.GET.matches(request.getMethod())
                && !path.equals("/api") && !path.startsWith("/api/")
                && !path.equals("/actuator") && !path.startsWith("/actuator/")
                && !path.equals("/error") && !path.startsWith("/error/");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider daoAuthenticationProvider(UserDetailsService userDetailsService,
                                                               PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(DaoAuthenticationProvider daoAuthenticationProvider) {
        return new ProviderManager(daoAuthenticationProvider);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtAuthenticationFilter,
                                           ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
                                           RestAuthenticationEntryPoint authenticationEntryPoint) throws Exception {
        return http
                // Stateless API with a token, so there is no session cookie for CSRF to protect.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // The container's internal forward to /error after an unhandled exception.
                        // The JWT filter does not run again on it, so without this every 500 or 404
                        // reached the client as a 401, which the frontend treats as "signed out".
                        // A request made to /error directly is a normal REQUEST and still needs a token.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // Only the unauthenticated entry points; /me and /logout need a token.
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/register", "/api/auth/login", "/api/auth/refresh").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/stripe/webhook").permitAll()
                        .requestMatchers("/status/**").permitAll()
                        // Public status page data; the SPA owns /status/{slug} itself.
                        .requestMatchers(HttpMethod.GET, "/api/status/*").permitAll()
                        // Heartbeat pings: the secret token in the path is the credential.
                        .requestMatchers("/api/ping/*").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/liveness",
                                "/actuator/health/readiness").permitAll()
                        // After health: first match wins, so health stays public.
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // Account security needs a real sign-in: a leaked API key must not mint
                        // more keys, change the password, or reach billing.
                        .requestMatchers("/api/api-keys", "/api/api-keys/**", "/api/auth/password",
                                "/api/billing/**").access(SESSION_ONLY)
                        // The frontend's own files and client-side routes (SpaConfig). Last, so
                        // every rule above still applies; /error stays closed as a direct request.
                        .requestMatchers(SecurityConfig::isFrontendRequest).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(authenticationEntryPoint))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiKeyAuthenticationFilter, JwtAuthenticationFilter.class)
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .build();
    }
}
