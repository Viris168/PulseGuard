package com.viris.PulseGuard.ai.help;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps {@code help_chunks} (V24) in step with the help docs, on every start (AI_MILESTONE_3.md).
 * Only sections whose text or embedding model changed are embedded again, so a normal restart
 * costs no embedding requests at all; removed sections are deleted.
 *
 * <p>Runs only when an {@link EmbeddingModel} is configured
 * ({@code spring.ai.model.embedding.text}), and never stops the app: if it fails, docs search
 * works from whatever was indexed before, or is simply unavailable. A transaction-level advisory
 * lock lets one instance index while others skip. The embedding calls happen inside that
 * transaction; that holds one connection for a few seconds at startup, which is acceptable here
 * and keeps "read what's stored, embed, write" consistent.
 */
@Component
public class HelpDocsIndexer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HelpDocsIndexer.class);

    /** pg_try_advisory_xact_lock key: any constant unique to this job. */
    static final long LOCK_KEY = 0x48454C50444F4353L; // "HELPDOCS"
    /** Texts per embedding request. */
    static final int BATCH = 50;

    /** How one run went. {@code skipped}: another instance held the lock. */
    public record Result(int chunks, int embedded, int deleted, boolean skipped) {
    }

    private final HelpArticles articles;
    private final ObjectProvider<EmbeddingModel> embeddingModel;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String modelName;

    public HelpDocsIndexer(HelpArticles articles, ObjectProvider<EmbeddingModel> embeddingModel, JdbcTemplate jdbc,
                           PlatformTransactionManager transactionManager,
                           @Value("${spring.ai.google.genai.embedding.text.options.model:unknown}") String modelName) {
        this.articles = articles;
        this.embeddingModel = embeddingModel;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.modelName = modelName;
    }

    @Override
    public void run(ApplicationArguments args) {
        EmbeddingModel model = embeddingModel.getIfAvailable();
        if (model == null) {
            log.info("Help docs search is off (spring.ai.model.embedding.text=none): not indexing");
            return;
        }
        try {
            Result r = index(model, articles.chunks());
            if (r.skipped()) {
                log.info("Help docs: another instance is indexing, skipped");
            } else {
                log.info("Help docs indexed: {} chunks, {} embedded, {} deleted (model {})",
                        r.chunks(), r.embedded(), r.deleted(), modelName);
            }
        } catch (RuntimeException e) {
            // The provider's message can echo the request; the class name is enough to go on.
            log.warn("Help docs indexing failed, docs search uses what was indexed before: {}",
                    e.getClass().getSimpleName());
        }
    }

    /** Brings the table in line with {@code chunks}, embedding only what changed. */
    public Result index(EmbeddingModel model, List<HelpArticles.Chunk> chunks) {
        return tx.execute(status -> {
            Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(locked)) {
                return new Result(chunks.size(), 0, 0, true);
            }
            Map<String, String> stored = new HashMap<>();
            jdbc.query("SELECT article, anchor, content_hash FROM help_chunks",
                    rs -> { stored.put(key(rs.getString(1), rs.getString(2)), rs.getString(3)); });

            List<HelpArticles.Chunk> changed = new ArrayList<>();
            List<String> hashes = new ArrayList<>();
            Set<String> wanted = new HashSet<>();
            for (HelpArticles.Chunk c : chunks) {
                String k = key(c.article(), c.anchor());
                wanted.add(k);
                String hash = hash(c);
                if (!hash.equals(stored.get(k))) {
                    changed.add(c);
                    hashes.add(hash);
                } else {
                    // Unchanged text keeps its vector; only its place in the article may have moved.
                    jdbc.update("UPDATE help_chunks SET position = ? WHERE article = ? AND anchor = ? AND position <> ?",
                            c.position(), c.article(), c.anchor(), c.position());
                }
            }

            for (int from = 0; from < changed.size(); from += BATCH) {
                List<HelpArticles.Chunk> batch = changed.subList(from, Math.min(from + BATCH, changed.size()));
                List<float[]> vectors = model.embed(batch.stream().map(HelpArticles.Chunk::embeddingText).toList());
                if (vectors.size() != batch.size()) {
                    throw new IllegalStateException("Got " + vectors.size() + " embeddings for " + batch.size() + " texts");
                }
                for (int i = 0; i < batch.size(); i++) {
                    upsert(batch.get(i), hashes.get(from + i), vectors.get(i));
                }
            }

            int deleted = 0;
            for (String k : stored.keySet()) {
                if (!wanted.contains(k)) {
                    String[] parts = k.split("#", 2);
                    deleted += jdbc.update("DELETE FROM help_chunks WHERE article = ? AND anchor = ?", parts[0], parts[1]);
                }
            }
            return new Result(chunks.size(), changed.size(), deleted, false);
        });
    }

    private void upsert(HelpArticles.Chunk c, String hash, float[] vector) {
        jdbc.update("""
                INSERT INTO help_chunks (article, title, heading, anchor, position, content, content_hash,
                                         embedding_model, embedding, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, now())
                ON CONFLICT (article, anchor) DO UPDATE SET
                    title = EXCLUDED.title, heading = EXCLUDED.heading, position = EXCLUDED.position,
                    content = EXCLUDED.content, content_hash = EXCLUDED.content_hash,
                    embedding_model = EXCLUDED.embedding_model, embedding = EXCLUDED.embedding, updated_at = now()
                """,
                c.article(), c.title(), c.heading(), c.anchor(), c.position(), c.content(), hash, modelName,
                vectorLiteral(vector));
    }

    /** The model is part of the hash: vectors from different models can't be compared. */
    String hash(HelpArticles.Chunk c) {
        String text = modelName + "\n" + c.title() + "\n" + c.heading() + "\n" + c.content();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }

    /** pgvector's text form, '[0.1,0.2,…]', bound with a ::vector cast (no driver extension needed). */
    static String vectorLiteral(float[] vector) {
        StringBuilder s = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                s.append(',');
            }
            s.append(vector[i]);
        }
        return s.append(']').toString();
    }

    private static String key(String article, String anchor) {
        return article + "#" + anchor;
    }
}
