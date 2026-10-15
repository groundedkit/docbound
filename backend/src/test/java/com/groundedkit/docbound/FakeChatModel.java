package com.groundedkit.docbound;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Streams a scripted answer token by token and records the prompts it receives. The last streamed response
 * carries token usage, like real providers do.
 */
public class FakeChatModel implements ChatModel {

	private volatile List<String> tokens = List.of("Answer", " text", " [1]", ".");

	private volatile RuntimeException failAfterTokens;

	private final List<Prompt> prompts = new ArrayList<>();

	private final AtomicInteger calls = new AtomicInteger();

	public FakeChatModel respondWith(String... tokens) {
		this.tokens = List.of(tokens);
		this.failAfterTokens = null;
		return this;
	}

	/** Streams the scripted tokens, then fails. */
	public FakeChatModel failAfterTokens(RuntimeException error) {
		this.failAfterTokens = error;
		return this;
	}

	public int calls() {
		return calls.get();
	}

	public Prompt lastPrompt() {
		return prompts.getLast();
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		throw new UnsupportedOperationException("The app only uses streaming");
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		calls.incrementAndGet();
		prompts.add(prompt);
		List<ChatResponse> responses = new ArrayList<>();
		tokens.forEach(token -> responses.add(new ChatResponse(List.of(new Generation(new AssistantMessage(token))))));
		Flux<ChatResponse> stream = Flux.fromIterable(responses);
		if (failAfterTokens != null) {
			return stream.concatWith(Flux.error(failAfterTokens));
		}
		ChatResponse last = new ChatResponse(List.of(new Generation(new AssistantMessage(""))),
				ChatResponseMetadata.builder().usage(new DefaultUsage(120, tokens.size())).build());
		return stream.concatWith(Flux.just(last));
	}

}
