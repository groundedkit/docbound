package com.groundedkit.docbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * Deterministic bag-of-words embeddings for tests: texts sharing words get similar vectors, so similarity
 * search behaves sensibly without running a real model.
 */
public class FakeEmbeddingModel implements EmbeddingModel {

	public static final int DIMENSIONS = 768;

	private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		List<String> texts = request.getInstructions();
		List<Embedding> embeddings = new ArrayList<>(texts.size());
		for (int i = 0; i < texts.size(); i++) {
			embeddings.add(new Embedding(embed(texts.get(i)), i));
		}
		return new EmbeddingResponse(embeddings);
	}

	@Override
	public float[] embed(Document document) {
		return embed(document.getText());
	}

	@Override
	public float[] embed(String text) {
		float[] vector = new float[DIMENSIONS];
		var words = WORD.matcher(text.toLowerCase(Locale.ROOT));
		while (words.find()) {
			vector[Math.floorMod(words.group().hashCode(), DIMENSIONS)] += 1;
		}
		double norm = 0;
		for (float v : vector) {
			norm += v * v;
		}
		if (norm == 0) {
			vector[0] = 1; // cosine distance is undefined for a zero vector
			return vector;
		}
		for (int i = 0; i < vector.length; i++) {
			vector[i] /= (float) Math.sqrt(norm);
		}
		return vector;
	}

	@Override
	public int dimensions() {
		return DIMENSIONS;
	}

}
