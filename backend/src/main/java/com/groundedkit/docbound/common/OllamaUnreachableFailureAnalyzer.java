package com.groundedkit.docbound.common;

import java.util.Arrays;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.env.Environment;
import org.springframework.web.client.ResourceAccessException;

/**
 * Replaces the long bean-creation stack trace printed when Ollama can't be reached at startup with a short
 * explanation and the steps to fix it. Spring AI checks for the configured models while creating its Ollama beans,
 * so a stopped Ollama otherwise surfaces as a chain of {@code UnsatisfiedDependencyException}s.
 */
class OllamaUnreachableFailureAnalyzer extends AbstractFailureAnalyzer<ResourceAccessException> {

	private static final String OLLAMA_PACKAGE = "org.springframework.ai.ollama";

	private final Environment environment;

	OllamaUnreachableFailureAnalyzer(Environment environment) {
		this.environment = environment;
	}

	@Override
	protected FailureAnalysis analyze(Throwable rootFailure, ResourceAccessException cause) {
		if (!thrownByOllamaClient(cause)) {
			return null;
		}
		String baseUrl = environment.getProperty("spring.ai.ollama.base-url", "http://localhost:11434");
		String reason = cause.getMostSpecificCause().getMessage();
		String description = """
				Couldn't connect to Ollama at %s (%s).
				The app needs a running Ollama to create embeddings and answer questions.""".formatted(baseUrl, reason);
		StringBuilder action = new StringBuilder("""
				Start Ollama: open the Ollama app, or run `ollama serve` (Homebrew: `brew services start ollama`).
				Check it's up with `ollama list`, then restart the backend (Docker Compose restarts it automatically).
				If Ollama runs somewhere else, set OLLAMA_BASE_URL for Docker Compose, or spring.ai.ollama.base-url.""");
		if (baseUrl.contains("host.docker.internal")) {
			action.append("""

					On Linux, Ollama only listens on 127.0.0.1 by default, which containers can't reach. Set
					OLLAMA_HOST=0.0.0.0 for the Ollama service, or run Ollama in Docker with docker-compose.ollama.yml.""");
		}
		return new FailureAnalysis(description, action.toString(), cause);
	}

	private static boolean thrownByOllamaClient(Throwable failure) {
		return Arrays.stream(failure.getStackTrace())
			.anyMatch(frame -> frame.getClassName().startsWith(OLLAMA_PACKAGE));
	}

}
