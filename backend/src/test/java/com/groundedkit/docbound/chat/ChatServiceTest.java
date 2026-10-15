package com.groundedkit.docbound.chat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.groundedkit.docbound.FakeChatModel;
import com.groundedkit.docbound.FakeEmbeddingModel;
import com.groundedkit.docbound.chat.ChatEvent.Citation;
import com.groundedkit.docbound.chat.ChatEvent.Done;
import com.groundedkit.docbound.chat.ChatEvent.Sources;
import com.groundedkit.docbound.chat.ChatEvent.Token;
import com.groundedkit.docbound.ingestion.IngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import static org.assertj.core.api.Assertions.assertThat;

class ChatServiceTest {

	private final UUID documentId = UUID.randomUUID();

	private final FakeChatModel model = new FakeChatModel();

	private final SimpleVectorStore vectorStore = SimpleVectorStore.builder(new FakeEmbeddingModel()).build();

	private ChatService service;

	@BeforeEach
	void setUp() {
		vectorStore.add(List.of(
				chunk(0, "## Vacation\n\nFull-time employees receive 27 days of paid vacation per year."),
				chunk(1, "## Expenses\n\nMeals are reimbursed up to 70 euros per day when travelling.")));
		service = new ChatService(ChatClient.builder(model), vectorStore, new ChatProperties(5, 0.3));
	}

	@Test
	void streamsSourcesThenTokensThenDone() {
		model.respondWith("You get", " 27 days", " [1]", ".");

		List<ChatEvent> events = service.answer("how many vacation days do employees receive", null).collectList().block();

		assertThat(events.getFirst()).isInstanceOf(Sources.class);
		Sources sources = (Sources) events.getFirst();
		assertThat(sources.sources()).isNotEmpty();
		assertThat(sources.sources().getFirst()).extracting(Citation::index, Citation::documentId, Citation::filename)
			.containsExactly(1, documentId, "handbook.md");

		assertThat(events.subList(1, events.size() - 1)).extracting(event -> ((Token) event).text())
			.containsExactly("You get", " 27 days", " [1]", ".");

		Done done = (Done) events.getLast();
		assertThat(done.citedSources()).containsExactly(1);
		assertThat(done.promptTokens()).isEqualTo(120);
		assertThat(done.completionTokens()).isEqualTo(4);
	}

	@Test
	void givesTheModelNumberedSourcesAndTheRules() {
		service.answer("how many vacation days do employees receive", 1).blockLast();

		List<Message> messages = model.lastPrompt().getInstructions();
		assertThat(messages.getFirst().getText()).contains(ChatService.NOT_FOUND).contains("[1]");
		assertThat(messages.getLast().getText()).startsWith("Sources:")
			.contains("[1] (from handbook.md)\n## Vacation")
			.doesNotContain("[2]")
			.endsWith("Question: how many vacation days do employees receive");
	}

	@Test
	void answersNotFoundWithoutCallingTheModelWhenNothingIsRelevant() {
		ChatService strict = new ChatService(ChatClient.builder(model), vectorStore, new ChatProperties(5, 0.99));

		List<ChatEvent> events = strict.answer("what is the capital of France", null).collectList().block();

		assertThat(events).containsExactly(new Sources(List.of()), new Token(ChatService.NOT_FOUND),
				new Done(List.of(), null, null, 0));
		assertThat(model.calls()).isZero();
	}

	@Test
	void sourceTextWithTemplateCharactersIsPassedThroughUnchanged() {
		vectorStore.add(List.of(chunk(2, "Vacation config example: {\"days\": 27, \"carry\": {max}} <end>")));

		service.answer("vacation config example days", null).blockLast();

		assertThat(model.lastPrompt().getInstructions().getLast().getText())
			.contains("{\"days\": 27, \"carry\": {max}} <end>");
	}

	@Test
	void modelFailureEndsTheStreamWithAnErrorEvent() {
		model.respondWith("Partial").failAfterTokens(new IllegalStateException("connection reset"));

		List<ChatEvent> events = service.answer("vacation days", null).collectList().block();

		assertThat(events).hasSize(3);
		assertThat(events.get(1)).isEqualTo(new Token("Partial"));
		assertThat(events.getLast()).isInstanceOf(ChatEvent.Error.class);
		assertThat(((ChatEvent.Error) events.getLast()).message()).doesNotContain("connection reset");
	}

	@Test
	void citedSourcesAreDedupedOrderedAndLimitedToRealSources() {
		assertThat(ChatService.citedSources("A [2]. B [1][2]. C [9]. D [0]. E [x].", 3)).containsExactly(2, 1);
		assertThat(ChatService.citedSources("No citations here.", 3)).isEmpty();
	}

	@Test
	void notFoundAnswerCitesNothingEvenIfTheModelAddsACitation() {
		assertThat(ChatService.citedSources(" " + ChatService.NOT_FOUND + " [1]", 3)).isEmpty();
		assertThat(ChatService.citedSources("27 days [1]. " + ChatService.NOT_FOUND, 3)).containsExactly(1);
	}

	private Document chunk(int index, String text) {
		return new Document(text, Map.of(IngestionService.DOCUMENT_ID, documentId.toString(),
				IngestionService.FILENAME, "handbook.md", IngestionService.CHUNK_INDEX, index));
	}

}
