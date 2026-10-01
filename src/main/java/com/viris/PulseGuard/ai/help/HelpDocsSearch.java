package com.viris.PulseGuard.ai.help;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions.TaskType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the help-doc sections that answer a question (AI_MILESTONE_3.md, Step 4). Two searches,
 * merged:
 * <ul>
 *   <li><b>by meaning</b>: the question's embedding against every section's, nearest first,
 *       ignoring sections below {@code min-similarity};</li>
 *   <li><b>by exact words</b>: Postgres full-text search, any of the question's words (Step 1:
 *       requiring all of them found nothing for "notified slack"), best ranked first. This is what
 *       finds "502" or "pg_live_", which embeddings blur.</li>
 * </ul>
 * The two rankings are merged by reciprocal rank fusion: each section scores
 * {@code 1 / (k + rank)} in every list it appears in, so one that ranks well in either rises.
 * An empty result means the docs don't cover the question.
 */
@Component
public class HelpDocsSearch {

    /** One section that matched, best first. {@code similarity} is null if only the words matched. */
    public record Hit(String article, String title, String heading, String anchor, String content,
                      Double similarity, double score) {

        public String url() {
            return "/docs/" + article + "#" + anchor;
        }
    }

    private final ObjectProvider<EmbeddingModel> embeddingModel;
    private final JdbcTemplate jdbc;
    private final HelpDocsProperties properties;
    private final String modelName;

    public HelpDocsSearch(ObjectProvider<EmbeddingModel> embeddingModel, JdbcTemplate jdbc, HelpDocsProperties properties,
                          @Value("${spring.ai.google.genai.embedding.text.options.model:unknown}") String modelName) {
        this.embeddingModel = embeddingModel;
        this.jdbc = jdbc;
        this.properties = properties;
        this.modelName = modelName;
    }

    /** False when no embedding model is configured: then Ask AI isn't offered docs search at all. */
    public boolean isAvailable() {
        return embeddingModel.getIfAvailable() != null;
    }

    public List<Hit> search(String question) {
        EmbeddingModel model = embeddingModel.getIfAvailable();
        if (model == null || question == null || question.isBlank()) {
            return List.of();
        }
        String vector = HelpDocsIndexer.vectorLiteral(embedQuestion(model, question));

        // By meaning. Only rows embedded by the current model: vectors from two models don't compare.
        Map<Long, Double> similarity = new LinkedHashMap<>();
        jdbc.query("""
                        SELECT id, 1 - (embedding <=> ?::vector) AS similarity
                        FROM help_chunks
                        WHERE embedding_model = ?
                        ORDER BY embedding <=> ?::vector
                        LIMIT ?""",
                rs -> {
                    double s = rs.getDouble(2);
                    if (s >= properties.minSimilarity()) {
                        similarity.put(rs.getLong(1), s);
                    }
                },
                vector, modelName, vector, properties.vectorCandidates());

        // By exact words: plainto_tsquery drops stop words and stems the rest; '&' becomes '|'.
        List<Long> byWords = properties.textCandidates() == 0 ? List.of() : jdbc.queryForList("""
                        SELECT id
                        FROM help_chunks,
                             (SELECT replace(plainto_tsquery('english', ?)::text, '&', '|')::tsquery AS q) words
                        WHERE search_text @@ q
                        ORDER BY ts_rank_cd(search_text, q) DESC, id
                        LIMIT ?""",
                Long.class, question, properties.textCandidates());

        Map<Long, Double> score = new HashMap<>();
        int rank = 1;
        for (Long id : similarity.keySet()) {
            score.merge(id, 1.0 / (properties.rrfK() + rank++), Double::sum);
        }
        rank = 1;
        for (Long id : byWords) {
            score.merge(id, 1.0 / (properties.rrfK() + rank++), Double::sum);
        }
        List<Long> best = score.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(properties.maxResults())
                .map(Map.Entry::getKey)
                .toList();
        if (best.isEmpty()) {
            return List.of();
        }

        Map<Long, Hit> rows = new HashMap<>();
        jdbc.query("SELECT id, article, title, heading, anchor, content FROM help_chunks WHERE id = ANY (?)",
                rs -> {
                    long id = rs.getLong(1);
                    rows.put(id, new Hit(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), similarity.get(id), score.get(id)));
                },
                (Object) best.toArray(Long[]::new));
        List<Hit> hits = new ArrayList<>();
        for (Long id : best) {
            Hit h = rows.get(id);
            if (h != null) {
                hits.add(h);
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits;
    }

    /** Gemini embeds questions and documents differently: RETRIEVAL_QUERY here, RETRIEVAL_DOCUMENT when indexing. */
    private float[] embedQuestion(EmbeddingModel model, String question) {
        return HelpEmbeddings.embed(model, modelName, List.of(question), TaskType.RETRIEVAL_QUERY).getFirst();
    }
}
