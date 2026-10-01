package com.viris.PulseGuard.ai.help;

import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

@Configuration
@EnableConfigurationProperties(HelpDocsProperties.class)
class HelpDocsConfig {

    /**
     * Replaces Spring AI's GoogleGenAiEmbeddingConnectionAutoConfiguration (excluded in application.yaml).
     * In Spring AI 2.0.1 that one ignores spring.ai.model.embedding.text and, with no API key, demands a
     * Vertex AI project id, so the app could not start with docs search off and no Google key (as in CI).
     * This builds the connection only when docs search is on, from an AI Studio key.
     */
    @Bean
    @ConditionalOnProperty(name = "spring.ai.model.embedding.text", havingValue = "google-genai")
    GoogleGenAiEmbeddingConnectionDetails googleGenAiEmbeddingConnectionDetails(
            @Value("${spring.ai.google.genai.embedding.api-key:}") String apiKey) {
        Assert.hasText(apiKey, "GOOGLE_AI_API_KEY must be set when PULSEGUARD_AI_EMBEDDING_PROVIDER=google-genai");
        return GoogleGenAiEmbeddingConnectionDetails.builder().apiKey(apiKey).build();
    }
}
