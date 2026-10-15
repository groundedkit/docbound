package com.groundedkit.docbound.common;

import java.net.ConnectException;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaUnreachableFailureAnalyzerTest {

	private final MockEnvironment environment = new MockEnvironment()
		.withProperty("spring.ai.ollama.base-url", "http://host.docker.internal:11434");

	private final OllamaUnreachableFailureAnalyzer analyzer = new OllamaUnreachableFailureAnalyzer(environment);

	@Test
	void explainsConnectionFailureFromTheOllamaClient() {
		FailureAnalysis analysis = analyzer.analyze(startupFailure(connectionRefused("org.springframework.ai.ollama.api.OllamaApi")));

		assertThat(analysis).isNotNull();
		assertThat(analysis.getDescription())
			.contains("Couldn't connect to Ollama at http://host.docker.internal:11434")
			.contains("Connection refused");
		assertThat(analysis.getAction()).contains("ollama serve").contains("ollama list").contains("OLLAMA_BASE_URL")
			.contains("OLLAMA_HOST=0.0.0.0");
	}

	@Test
	void omitsTheLinuxHintWhenNotUsingTheDockerHostAlias() {
		environment.setProperty("spring.ai.ollama.base-url", "http://localhost:11434");

		FailureAnalysis analysis = analyzer.analyze(startupFailure(connectionRefused("org.springframework.ai.ollama.api.OllamaApi")));

		assertThat(analysis.getAction()).doesNotContain("OLLAMA_HOST");
	}

	@Test
	void ignoresConnectionFailuresFromOtherClients() {
		assertThat(analyzer.analyze(startupFailure(connectionRefused("com.example.SomeOtherClient")))).isNull();
	}

	@Test
	void ignoresUnrelatedStartupFailures() {
		assertThat(analyzer.analyze(new IllegalStateException("Port 8080 already in use"))).isNull();
	}

	private static ResourceAccessException connectionRefused(String thrownBy) {
		var exception = new ResourceAccessException("I/O error on GET request for \"http://host.docker.internal:11434/api/tags\"",
				new ConnectException("Connection refused"));
		exception.setStackTrace(new StackTraceElement[] { new StackTraceElement(thrownBy, "listModels", null, 1) });
		return exception;
	}

	private static Throwable startupFailure(Throwable cause) {
		return new BeanCreationException("chatController", "Unsatisfied dependency",
				new BeanCreationException("ollamaChatModel", "Failed to instantiate", cause));
	}

}
