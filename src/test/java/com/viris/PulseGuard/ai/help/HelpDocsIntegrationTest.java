package com.viris.PulseGuard.ai.help;

import com.viris.PulseGuard.TestDatabase;
import com.viris.PulseGuard.auth.security.InMemoryLoginRateLimiter;
import com.viris.PulseGuard.auth.security.InMemoryTokenDenylist;
import com.viris.PulseGuard.auth.security.LoginRateLimiter;
import com.viris.PulseGuard.auth.security.TokenDenylist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The indexer and the search against a real pgvector database, with a fake embedding model that
 * records what it was asked to embed: nothing unchanged is embedded twice, and search finds
 * sections by meaning (cosine) and by exact words (full-text). How good the search is with a
 * real model is HelpDocsSearchEvalTest's job.
 */
@SpringBootTest(properties = "spring.ai.google.genai.embedding.text.options.model=test-embedding-a")
class HelpDocsIntegrationTest {

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
        FakeEmbeddingModel fakeEmbeddingModel() {
            return new FakeEmbeddingModel();
        }
    }

    @Autowired
    HelpDocsIndexer indexer;
    @Autowired
    HelpDocsSearch search;
    @Autowired
    HelpArticles articles;
    @Autowired
    FakeEmbeddingModel model;
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
        model.embedded.clear();
    }

    @Test
    void embedsEverySectionOfTheRealDocsOnceThenNothingOnTheNextStart() {
        List<HelpArticles.Chunk> chunks = articles.chunks();

        HelpDocsIndexer.Result first = indexer.index(model, chunks);
        int afterFirst = model.embedded.size();
        HelpDocsIndexer.Result second = indexer.index(model, chunks);

        assertThat(chunks).hasSizeGreaterThan(60);
        assertThat(first).isEqualTo(new HelpDocsIndexer.Result(chunks.size(), chunks.size(), 0, false));
        assertThat(afterFirst).isEqualTo(chunks.size());
        assertThat(second.embedded()).isZero();
        assertThat(model.embedded).hasSize(afterFirst);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM help_chunks", Integer.class)).isEqualTo(chunks.size());
    }

    @Test
    void embedsEachSectionWithItsTitles() {
        indexer.index(model, List.of(chunk("slack-alerts", "Setting it up", 0, "Create an incoming webhook.")));

        assertThat(model.embedded).containsExactly("Slack alerts › Setting it up\n\nCreate an incoming webhook.");
    }

    @Test
    void reEmbedsOnlyTheSectionThatChanged() {
        indexer.index(model, List.of(
                chunk("slack-alerts", "Setting it up", 0, "Create an incoming webhook."),
                chunk("slack-alerts", "After a downgrade", 1, "Slack channels are switched off.")));
        model.embedded.clear();

        HelpDocsIndexer.Result r = indexer.index(model, List.of(
                chunk("slack-alerts", "Setting it up", 0, "Create an incoming webhook for a channel."),
                chunk("slack-alerts", "After a downgrade", 1, "Slack channels are switched off.")));

        assertThat(r.embedded()).isEqualTo(1);
        assertThat(model.embedded).singleElement().asString().contains("for a channel");
        assertThat(jdbc.queryForObject("SELECT content FROM help_chunks WHERE anchor = 'setting-it-up'", String.class))
                .isEqualTo("Create an incoming webhook for a channel.");
    }

    @Test
    void movingASectionUpdatesItsPlaceWithoutEmbeddingItAgain() {
        indexer.index(model, List.of(chunk("a", "First", 0, "one"), chunk("a", "Second", 1, "two")));
        model.embedded.clear();

        indexer.index(model, List.of(chunk("a", "Second", 0, "two"), chunk("a", "First", 1, "one")));

        assertThat(model.embedded).isEmpty();
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
        model.embedded.clear();
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
        String own = HelpDocsIndexer.vectorLiteral(FakeEmbeddingModel.vectorFor("Slack alerts › Setting it up\n\nCreate an incoming webhook."));
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
            assertThat(model.embedded).isEmpty();
            other.rollback();
        }
    }

    // ─── search ──────────────────────────────────────────────────────────────

    private static final HelpArticles.Chunk SLACK = chunk("slack-alerts", "Setting it up", 0, "Create an incoming webhook.");
    private static final HelpArticles.Chunk CODES = chunk("status-codes", "5xx: the server failed", 0,
            "502 Bad Gateway: a proxy got no valid answer.");
    private static final HelpArticles.Chunk KEYS = chunk("api-keys", "Creating a key", 0, "Keys start with pg_live_.");

    @Test
    void findsASectionByMeaning() {
        indexer.index(model, List.of(SLACK, CODES, KEYS));

        // The fake model gives identical text identical vectors: a perfect match by meaning.
        List<HelpDocsSearch.Hit> hits = search.search(SLACK.embeddingText());

        assertThat(hits.getFirst().anchor()).isEqualTo("setting-it-up");
        assertThat(hits.getFirst().similarity()).isGreaterThan(0.99);
        assertThat(hits.getFirst().url()).isEqualTo("/docs/slack-alerts#setting-it-up");
    }

    @Test
    void findsExactWordsThatMeaningAloneWouldMiss() {
        indexer.index(model, List.of(SLACK, CODES, KEYS));

        // Unrelated vectors, so nothing passes min-similarity: only full-text can find these.
        assertThat(search.search("what does 502 mean?")).extracting(HelpDocsSearch.Hit::article).containsExactly("status-codes");
        assertThat(search.search("my pg_live_ key")).extracting(HelpDocsSearch.Hit::article).contains("api-keys");
        assertThat(search.search("what does 502 mean?").getFirst().similarity()).isNull();
    }

    @Test
    void findsNothingWhenNeitherMeaningNorWordsMatch() {
        indexer.index(model, List.of(SLACK, CODES, KEYS));

        assertThat(search.search("What's the capital of France?")).isEmpty();
        assertThat(search.search("   ")).isEmpty();
    }

    @Test
    void returnsAtMostMaxResultsBestFirst() {
        List<HelpArticles.Chunk> many = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            many.add(chunk("alerts-" + i, "Alerts part " + i, 0, "Alert details, alert number " + i + "."));
        }
        indexer.index(model, many);

        List<HelpDocsSearch.Hit> hits = search.search("alert");

        assertThat(hits).hasSize(4);
        assertThat(hits).isSortedAccordingTo(java.util.Comparator.comparingDouble(HelpDocsSearch.Hit::score).reversed());
    }

    @Test
    void ignoresVectorsFromAnotherEmbeddingModel() {
        new HelpDocsIndexer(articles, models, jdbc, transactions, "test-embedding-b").index(model, List.of(SLACK));

        // Same text, so the vectors would match perfectly, but they come from another model.
        List<HelpDocsSearch.Hit> hits = search.search(SLACK.embeddingText());

        assertThat(hits).extracting(HelpDocsSearch.Hit::similarity).containsOnlyNulls();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static HelpArticles.Chunk chunk(String article, String heading, int position, String content) {
        String title = article.equals("slack-alerts") ? "Slack alerts" : article;
        return new HelpArticles.Chunk(article, title, heading, HelpArticles.anchor(heading), position, content);
    }
}
