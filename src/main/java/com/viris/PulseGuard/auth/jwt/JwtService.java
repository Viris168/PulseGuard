package com.viris.PulseGuard.auth.jwt;

import com.viris.PulseGuard.auth.UserPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Issues and verifies tokens. Tokens carry identity and nothing else: authority and plan are
 * looked up per request, so a role change or plan downgrade is not stale for an hour.
 */
@Service
public class JwtService {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_TYPE = "typ";

    private final SecretKey signingKey;
    private final List<JwtParser> parsers;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = keyFrom(properties.secret());

        // Signing always uses the current key; verification also accepts the previous one so a
        // rotation does not sign every user out.
        this.parsers = new ArrayList<>();
        this.parsers.add(parserFor(signingKey));
        if (StringUtils.hasText(properties.previousSecret())) {
            this.parsers.add(parserFor(keyFrom(properties.previousSecret())));
        }
    }

    private static SecretKey keyFrom(String base64Secret) {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(base64Secret));
    }

    private JwtParser parserFor(SecretKey key) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .requireAudience(properties.audience())
                .clockSkewSeconds(properties.clockSkew().toSeconds())
                .build();
    }

    public String generateAccessToken(UserPrincipal principal) {
        return generate(principal, TokenType.ACCESS, properties.accessExpiration().toSeconds());
    }

    public String generateRefreshToken(UserPrincipal principal) {
        return generate(principal, TokenType.REFRESH, properties.refreshExpiration().toSeconds());
    }

    public long accessTokenSeconds() {
        return properties.accessExpiration().toSeconds();
    }

    /** How long a cut-off must be remembered for it to outlive every token it revokes. */
    public java.time.Duration sessionRetention() {
        return properties.refreshExpiration();
    }

    private String generate(UserPrincipal principal, TokenType type, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())          // jti: the handle the denylist uses
                .issuer(properties.issuer())
                .audience().add(properties.audience()).and()
                .subject(String.valueOf(principal.getUserId()))
                .claim(CLAIM_EMAIL, principal.getEmail())
                .claim(CLAIM_TYPE, type.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Verifies signature (current or previous key), expiry, issuer and audience.
     *
     * @throws JwtException if the token is not one of ours, expired, or malformed.
     */
    public ParsedToken parse(String token) {
        JwtException lastFailure = null;

        for (JwtParser parser : parsers) {
            try {
                return toParsedToken(parser.parseSignedClaims(token).getPayload());
            } catch (JwtException ex) {
                lastFailure = ex; // Try the previous key before giving up.
            }
        }
        throw lastFailure == null ? new MalformedJwtException("No verification key configured") : lastFailure;
    }

    private ParsedToken toParsedToken(Claims claims) {
        return new ParsedToken(
                parseUserId(claims),
                claims.get(CLAIM_EMAIL, String.class),
                claims.getId(),
                parseType(claims),
                parseIssuedAt(claims),
                claims.getExpiration().toInstant()
        );
    }

    // A claim of the wrong shape is a malformed token, not an unchecked runtime failure.
    private Long parseUserId(Claims claims) {
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException | NullPointerException ex) {
            throw new MalformedJwtException("Token subject is not a user id");
        }
    }

    private TokenType parseType(Claims claims) {
        try {
            return TokenType.valueOf(claims.get(CLAIM_TYPE, String.class));
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new MalformedJwtException("Token has no usable type claim");
        }
    }

    private Instant parseIssuedAt(Claims claims) {
        Date issuedAt = claims.getIssuedAt();
        if (issuedAt == null) {
            throw new MalformedJwtException("Token has no issued-at claim");
        }
        return issuedAt.toInstant();
    }
}
