package com.viris.PulseGuard.ai.help;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An embedding model for tests: the same text always gets the same 768 numbers (seeded by the
 * text), different texts get unrelated ones, and every text it's asked to embed is recorded.
 * So a question identical to a section's text is a perfect match by meaning, and nothing else is.
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    public final List<String> embedded = new CopyOnWriteArrayList<>();

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> out = new ArrayList<>();
        for (int i = 0; i < request.getInstructions().size(); i++) {
            String text = request.getInstructions().get(i);
            embedded.add(text);
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
        return HelpEmbeddings.DIMENSIONS;
    }

    static float[] vectorFor(String text) {
        Random random = new Random(text.hashCode());
        float[] v = new float[HelpEmbeddings.DIMENSIONS];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) random.nextGaussian();
        }
        return v;
    }
}
