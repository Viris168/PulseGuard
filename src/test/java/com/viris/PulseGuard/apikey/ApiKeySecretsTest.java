package com.viris.PulseGuard.apikey;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeySecretsTest {

    @Test
    void generatesPrefixedBase62Keys() {
        String key = ApiKeySecrets.generate();

        assertThat(key).startsWith("pg_live_").hasSize(40).matches("pg_live_[A-Za-z0-9]{32}");
        assertThat(ApiKeySecrets.looksLikeKey(key)).isTrue();
        assertThat(ApiKeySecrets.displayPrefix(key)).isEqualTo(key.substring(0, 12));
    }

    @Test
    void neverRepeatsAKey() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            keys.add(ApiKeySecrets.generate());
        }
        assertThat(keys).hasSize(1000);
    }

    @Test
    void hashesToStableHexSha256() {
        assertThat(ApiKeySecrets.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void jwtsAndShortTokensAreNotKeys() {
        assertThat(ApiKeySecrets.looksLikeKey("eyJhbGciOiJIUzI1NiJ9.e30.abc")).isFalse();
        assertThat(ApiKeySecrets.looksLikeKey("pg_live_short")).isFalse();
    }
}
