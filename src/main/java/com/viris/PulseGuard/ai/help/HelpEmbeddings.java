package com.viris.PulseGuard.ai.help;

import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions.TaskType;

import java.util.List;

/**
 * Every help-docs embedding request goes through here, with the model and dimensions set
 * explicitly. Setting only some options looks like it merges with the configured ones, but
 * Spring AI 2.0.1 then quietly uses gemini-embedding-001: 768 numbers in a different space
 * (similarity ~0.06 to the right answer; found by the golden set, AI_MILESTONE_3.md Step 4). The
 * model the provider reports is checked too, because that failure is otherwise invisible.
 */
final class HelpEmbeddings {

    /** help_chunks.embedding is vector(768) (V24). */
    static final int DIMENSIONS = 768;

    private HelpEmbeddings() {
    }

    static List<float[]> embed(EmbeddingModel model, String modelName, List<String> texts, TaskType taskType) {
        EmbeddingResponse response = model.call(new EmbeddingRequest(texts, GoogleGenAiTextEmbeddingOptions.builder()
                .model(modelName)
                .dimensions(DIMENSIONS)
                .taskType(taskType)
                .build()));
        String used = response.getMetadata() == null ? null : response.getMetadata().getModel();
        if (used != null && !used.isBlank() && !used.equals(modelName)) {
            throw new IllegalStateException("Embedded with " + used + " instead of " + modelName);
        }
        List<float[]> vectors = response.getResults().stream().map(Embedding::getOutput).toList();
        if (vectors.size() != texts.size()) {
            throw new IllegalStateException("Got " + vectors.size() + " embeddings for " + texts.size() + " texts");
        }
        for (float[] v : vectors) {
            if (v.length != DIMENSIONS) {
                throw new IllegalStateException("Got a " + v.length + "-dimension embedding, expected " + DIMENSIONS);
            }
        }
        return vectors;
    }
}
