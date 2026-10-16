package com.groundedkit.docbound;

import java.util.ArrayList;
import java.util.List;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/**
 * Streams a scripted answer token by token and records the prompts it receives. The last streamed response
 * carries token usage, like real providers do. Non-streaming calls (used to rewrite follow-up questions) return a
 * separately scripted reply.
 */
public class FakeChatModel implements ChatModel {

	private volatile List<String> tokens = List.of("Answer", " text", " [1]", ".");

	private volatile RuntimeException failAfterTokens;

	private volatile String callReply = null;

	private volatile RuntimeException callFailure;

	private final List<Prompt> streamPrompts = new ArrayList<>();

	private final List<Prompt> callPrompts = new ArrayList<>();

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

	/** The reply to a non-streaming call, e.g. the rewritten follow-up question. */
	public FakeChatModel callReturns(String reply) {
		this.callReply = reply;
		this.callFailure = null;
		return this;
	}

	public FakeChatModel callFails(RuntimeException error) {
		this.callFailure = error;
		return this;
	}

	public int streamCalls() {
		return streamPrompts.size();
	}

	public int calls() {
		return callPrompts.size();
	}

	public Prompt lastPrompt() {
		return streamPrompts.getLast();
	}

	public Prompt lastCallPrompt() {
		return callPrompts.getLast();
	}

	/** Like the real model: ChatClient merges per-request options into this type, so Ollama options survive. */
	@Override
	public ChatOptions getOptions() {
		return OllamaChatOptions.builder().build();
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		callPrompts.add(prompt);
		if (callFailure != null) {
			throw callFailure;
		}
		String reply = callReply != null ? callReply : prompt.getInstructions().getLast().getText();
		return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		streamPrompts.add(prompt);
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
