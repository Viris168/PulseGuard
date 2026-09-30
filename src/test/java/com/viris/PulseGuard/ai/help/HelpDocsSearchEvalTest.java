package com.viris.PulseGuard.ai.help;

import com.viris.PulseGuard.TestDatabase;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The golden set (src/test/resources/help-eval.yaml) against the real embedding model: how often
 * the right section is in the top results (AI_MILESTONE_3.md, Step 4). Costs real embedding
 * requests (the 85 sections, then one per question), so it runs only on demand:
 *
 * <pre>GOOGLE_AI_API_KEY=… ./mvnw test -Dtest=HelpDocsSearchEvalTest -Deval=true</pre>
 *
 * Compare settings by overriding them, e.g. {@code -Dpulseguard.ai.help.min-similarity=0.55},
 * {@code -Dpulseguard.ai.help.text-candidates=0} (meaning only) or
 * {@code -Dspring.ai.google.genai.embedding.text.options.model=gemini-embedding-001}.
 */
@Tag("eval")
@EnabledIfSystemProperty(named = "eval", matches = "true")
@SpringBootTest(properties = {
        "spring.ai.model.embedding.text=google-genai",
        "spring.ai.google.genai.embedding.api-key=${GOOGLE_AI_API_KEY}"
})
class HelpDocsSearchEvalTest {

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
    }

    @Autowired
    HelpDocsSearch search;
    @Autowired
    HelpDocsProperties settings;
    @Value("${spring.ai.google.genai.embedding.text.options.model}")
    String model;

    @Test
    @SuppressWarnings("unchecked")
    void theGoldenSet() throws IOException {
        Map<String, Object> set;
        try (InputStream in = getClass().getResourceAsStream("/help-eval.yaml")) {
            set = new Yaml().load(in);
        }
        List<Map<String, Object>> answerable = (List<Map<String, Object>>) set.get("answerable");
        List<String> unanswerable = (List<String>) set.get("unanswerable");

        StringBuilder report = new StringBuilder("\n=== Help docs search eval: model=" + model + " " + settings + "\n");
        int top1 = 0;
        int top4 = 0;
        double reciprocal = 0;
        for (Map<String, Object> item : answerable) {
            String q = (String) item.get("q");
            List<String> expect = (List<String>) item.get("expect");
            List<HelpDocsSearch.Hit> hits = search.search(q);
            int rank = 0;
            for (int i = 0; i < hits.size(); i++) {
                if (matches(hits.get(i), expect)) {
                    rank = i + 1;
                    break;
                }
            }
            top1 += rank == 1 ? 1 : 0;
            top4 += rank >= 1 ? 1 : 0;
            reciprocal += rank >= 1 ? 1.0 / rank : 0;
            report.append(rank >= 1 ? String.format(Locale.ROOT, "  #%d   ", rank) : "  MISS ").append(q)
                    .append(rank == 1 ? "" : "\n         got: " + describe(hits)).append('\n');
        }
        int empty = 0;
        report.append("--- unanswerable (should come back empty)\n");
        for (String q : unanswerable) {
            List<HelpDocsSearch.Hit> hits = search.search(q);
            empty += hits.isEmpty() ? 1 : 0;
            report.append(hits.isEmpty() ? "  empty " : "  FOUND ").append(q)
                    .append(hits.isEmpty() ? "" : "\n         got: " + describe(hits)).append('\n');
        }
        int n = answerable.size();
        report.append(String.format(Locale.ROOT,
                "--- hit@1 %d/%d (%.0f%%), hit@4 %d/%d (%.0f%%), MRR %.2f, unanswerable empty %d/%d%n",
                top1, n, 100.0 * top1 / n, top4, n, 100.0 * top4 / n, reciprocal / n, empty, unanswerable.size()));
        System.out.println(report);

        assertThat((double) top4 / n).as("hit@4 (target 90%%)").isGreaterThanOrEqualTo(0.9);
    }

    private static boolean matches(HelpDocsSearch.Hit hit, List<String> expect) {
        return expect.contains(hit.article() + "#" + hit.anchor()) || expect.contains(hit.article());
    }

    private static String describe(List<HelpDocsSearch.Hit> hits) {
        if (hits.isEmpty()) {
            return "(nothing)";
        }
        StringBuilder s = new StringBuilder();
        for (HelpDocsSearch.Hit h : hits) {
            s.append(h.article()).append('#').append(h.anchor())
                    .append(h.similarity() == null ? " (words only)" : String.format(Locale.ROOT, " (%.2f)", h.similarity()))
                    .append("; ");
        }
        return s.toString();
    }
}
