package com.viris.PulseGuard.auth.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param secret            HMAC-SHA key, base64-encoded, at least 256 bits. From the environment.
 * @param previousSecret    optional. During a key rotation, set this to the old secret: tokens
 *                          signed with it still verify, while new ones use {@code secret}. Clear
 *                          it once the longest refresh token has expired.
 * @param issuer            written as {@code iss} and required on every token we accept.
 * @param audience          written as {@code aud} and required on every token we accept.
 * @param accessExpiration  short, because an access token cannot be revoked cheaply.
 * @param refreshExpiration long; rotated and denylisted on every use.
 * @param clockSkew         tolerance for {@code exp}/{@code iat} against a peer's clock drift.
 */
@ConfigurationProperties(prefix = "pulseguard.jwt")
public record JwtProperties(
        String secret,
        String previousSecret,
        String issuer,
        String audience,
        Duration accessExpiration,
        Duration refreshExpiration,
        Duration clockSkew
) {
}
