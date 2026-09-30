package com.viris.PulseGuard.ai.help;

import com.viris.PulseGuard.TestDatabase;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The indexer against a real pgvector database, with a fake embedding model that records what
 * it was asked to embed: nothing unchanged is embedded twice, and the stored rows can be
 * searched both by meaning (cosine) and by exact words (full-text).
 */
@SpringBootTest(properties = "spring.ai.google.genai.embedding.text.options.model=test-embedding-a")
class HelpDocsIndexerIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestDatabase.IMAGE);

    static {
        POSTGRES.start();
    }

    /** Every text it was asked to embed, in order. */
    static final List<String> EMBEDDED = new CopyOnWriteArrayList<>();

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

        /** Same text, same vector: a seeded random unit-ish vector, like a real model's output. */
        @Bean
        EmbeddingModel fakeEmbeddingModel() {
            return new EmbeddingModel() {
                @Override
                public EmbeddingResponse call(EmbeddingRequest request) {
                    List<Embedding> out = new ArrayList<>();
                    for (int i = 0; i < request.getInstructions().size(); i++) {
                        String text = request.getInstructions().get(i);
                        EMBEDDED.add(text);
                        out.add(new Embedding(vectorFor(text), i));
                    }
                    return new EmbeddingResponse(out);
                }

                @Override
                public float[] embed(Document document) {
                    return vectorFor(document.getText());
                }

                @Override
                public int dimensions() {
                    return 768;
                }
            };
        }
    }

    @Autowired
    HelpDocsIndexer indexer;
    @Autowired
    HelpArticles articles;
    @Autowired
    EmbeddingModel model;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;
    @Autowired
    ObjectProvider<EmbeddingModel> models;
    @Autowired
    PlatformTransactionManager transactions;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM help_chunks");
        EMBEDDED.clear();
    }

    @Test
    void embedsEverySectionOfTheRealDocsOnceThenNothingOnTheNextStart() {
        List<HelpArticles.Chunk> chunks = articles.chunks();

        HelpDocsIndexer.Result first = indexer.index(model, chunks);
        int afterFirst = EMBEDDED.size();
        HelpDocsIndexer.Result second = indexer.index(model, chunks);

        assertThat(chunks).hasSizeGreaterThan(60);
        assertThat(first).isEqualTo(new HelpDocsIndexer.Result(chunks.size(), chunks.size(), 0, false));
        assertThat(afterFirst).isEqualTo(chunks.size());
        assertThat(second.embedded()).isZero();
        assertThat(EMBEDDED).hasSize(afterFirst);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM help_chunks", Integer.class)).isEqualTo(chunks.size());
    }

    @Test
    void embedsEachSectionWithItsTitles() {
        indexer.index(model, List.of(chunk("slack-alerts", "Setting it up", 0, "Create an incoming webhook.")));

        assertThat(EMBEDDED).containsExactly("Slack alerts › Setting it up\n\nCreate an incoming webhook.");
    }

    @Test
    void reEmbedsOnlyTheSectionThatChanged() {
        indexer.index(model, List.of(
                chunk("slack-alerts", "Setting it up", 0, "Create an incoming webhook."),
                chunk("slack-alerts", "After a downgrade", 1, "Slack channels are switched off.")));
        EMBEDDED.clear();

        HelpDocsIndexer.Result r = indexer.index(model, List.of(
                chunk("slack-alerts", "Setting it up", 0, "Create an incoming webhook for a channel."),
                chunk("slack-alerts", "After a downgrade", 1, "Slack channels are switched off.")));

        assertThat(r.embedded()).isEqualTo(1);
        assertThat(EMBEDDED).singleElement().asString().contains("for a channel");
        assertThat(jdbc.queryForObject("SELECT content FROM help_chunks WHERE anchor = 'setting-it-up'", String.class))
                .isEqualTo("Create an incoming webhook for a channel.");
    }

    @Test
    void movingASectionUpdatesItsPlaceWithoutEmbeddingItAgain() {
        indexer.index(model, List.of(chunk("a", "First", 0, "one"), chunk("a", "Second", 1, "two")));
        EMBEDDED.clear();

        indexer.index(model, List.of(chunk("a", "Second", 0, "two"), chunk("a", "First", 1, "one")));

        assertThat(EMBEDDED).isEmpty();
        assertThat(jdbc.queryForObject("SELECT position FROM help_chunks WHERE anchor = 'second'", Integer.class)).isZero();
    }

    @Test
    void removesSectionsAndArticlesThatNoLongerExist() {
        indexer.index(model, List.of(chunk("a", "Kept", 0, "x"), chunk("a", "Gone", 1, "y"), chunk("b", "Also gone", 0, "z")));

        HelpDocsIndexer.Result r = indexer.index(model, List.of(chunk("a", "Kept", 0, "x")));

        assertThat(r.deleted()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT article || '#' || anchor FROM help_chunks", String.class))
                .containsExactly("a#kept");
    }

    @Test
    void switchingTheEmbeddingModelReEmbedsEverything() {
        List<HelpArticles.Chunk> chunks = List.of(chunk("a", "One", 0, "x"), chunk("a", "Two", 1, "y"));
        indexer.index(model, chunks);
        EMBEDDED.clear();
        HelpDocsIndexer otherModel = new HelpDocsIndexer(articles, models, jdbc, transactions, "test-embedding-b");

        HelpDocsIndexer.Result r = otherModel.index(model, chunks);

        assertThat(r.embedded()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT DISTINCT embedding_model FROM help_chunks", String.class))
                .containsExactly("test-embedding-b");
    }

    @Test
    void storedChunksCanBeFoundByMeaningAndByExactWords() {
        indexer.index(model, List.of(
                chunk("status-codes", "5xx: the server failed", 0, "502 Bad Gateway: a proxy got no valid answer."),
                chunk("slack-alerts", "Setting it up", 0, "Create an incoming webhook.")));

        // Cosine: a chunk's own vector is its nearest neighbour.
        String own = HelpDocsIndexer.vectorLiteral(vectorFor("Slack alerts › Setting it up\n\nCreate an incoming webhook."));
        assertThat(jdbc.queryForObject("SELECT article FROM help_chunks ORDER BY embedding <=> ?::vector LIMIT 1",
                String.class, own)).isEqualTo("slack-alerts");
        // Full-text: the generated search_text column finds an exact status code.
        assertThat(jdbc.queryForList("SELECT article FROM help_chunks WHERE search_text @@ to_tsquery('english', '502')",
                String.class)).containsExactly("status-codes");
    }

    @Test
    void skipsWhileAnotherInstanceIsIndexing() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, HelpDocsIndexer.LOCK_KEY);
                lock.execute();
            }

            HelpDocsIndexer.Result r = indexer.index(model, List.of(chunk("a", "One", 0, "x")));

            assertThat(r.skipped()).isTrue();
            assertThat(EMBEDDED).isEmpty();
            other.rollback();
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static HelpArticles.Chunk chunk(String article, String heading, int position, String content) {
        String title = article.equals("slack-alerts") ? "Slack alerts" : article;
        return new HelpArticles.Chunk(article, title, heading, HelpArticles.anchor(heading), position, content);
    }

    static float[] vectorFor(String text) {
        Random random = new Random(new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).hashCode());
        float[] v = new float[768];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) random.nextGaussian();
        }
        return v;
    }
}
