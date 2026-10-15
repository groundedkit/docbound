package com.groundedkit.docbound.chat;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import reactor.core.publisher.Flux;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ChatController {

	private final ChatService chat;

	ChatController(ChatService chat) {
		this.chat = chat;
	}

	/**
	 * Streams the answer as server-sent events: {@code sources}, then {@code token}s, then {@code done} (or
	 * {@code error}). Each event's data is JSON; see {@link ChatEvent} for the shapes.
	 */
	@PostMapping(path = "/api/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	Flux<ServerSentEvent<ChatEvent>> chat(@Valid @RequestBody ChatRequest request) {
		List<ChatService.Turn> history = request.history() == null ? List.of()
				: request.history().stream().map(turn -> new ChatService.Turn(turn.question(), turn.answer())).toList();
		return chat.answer(request.question(), history, request.topK())
			.map(event -> ServerSentEvent.builder(event).event(event.name()).build());
	}

	/**
	 * @param history earlier exchanges, oldest first. Only the most recent {@code app.chat.history-turns} are used.
	 */
	record ChatRequest(@NotBlank @Size(max = 2000) String question, @Min(1) @Max(10) Integer topK,
			@Valid @Size(max = 20) List<HistoryTurn> history) {
	}

	record HistoryTurn(@NotBlank @Size(max = 2000) String question, @NotNull @Size(max = 10_000) String answer) {
	}

}
