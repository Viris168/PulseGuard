package com.viris.PulseGuard.ai.help;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How Ask AI searches the help docs (AI_MILESTONE_3.md, Step 4). Tuned with the golden set in
 * {@code src/test/resources/help-eval.yaml}: change one, rerun the eval, compare hit@4.
 *
 * @param vectorCandidates nearest sections by meaning taken into the fusion
 * @param textCandidates   best sections by exact words taken into the fusion
 * @param maxResults       sections returned to the model
 * @param minSimilarity    cosine similarity below which a section doesn't count as a match by
 *                         meaning; per embedding model, since off-topic questions still score
 *                         0.44–0.53 (Step 1)
 * @param rrfK             reciprocal rank fusion's constant: 1 / (k + rank)
 */
@Validated
@ConfigurationProperties(prefix = "pulseguard.ai.help")
public record HelpDocsProperties(@Min(1) int vectorCandidates,
                                 @Min(0) int textCandidates,
                                 @Min(1) int maxResults,
                                 @DecimalMin("0") @DecimalMax("1") double minSimilarity,
                                 @Min(1) int rrfK) {
}
