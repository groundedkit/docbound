package com.groundedkit.docbound.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.groundedkit.docbound.FakeChatModel;
import com.groundedkit.docbound.FakeEmbeddingModel;
import com.groundedkit.docbound.chat.ChatEvent.Citation;
import com.groundedkit.docbound.chat.ChatEvent.Done;
import com.groundedkit.docbound.chat.ChatEvent.Sources;
import com.groundedkit.docbound.chat.ChatEvent.Token;
import com.groundedkit.docbound.chat.ChatService.Turn;
import com.groundedkit.docbound.ingestion.DocumentRecord;
import com.groundedkit.docbound.ingestion.DocumentRepository;
import com.groundedkit.docbound.ingestion.IngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatServiceTest {

	private final UUID documentId = UUID.randomUUID();

	private final FakeChatModel model = new FakeChatModel();

	private final SimpleVectorStore vectorStore = SimpleVectorStore.builder(new FakeEmbeddingModel()).build();

	private final DocumentRepository documents = mock(DocumentRepository.class);

	private ChatService service;

	@BeforeEach
	void setUp() {
		vectorStore.add(List.of(
				chunk(0, "## Vacation\n\nFull-time employees receive 27 days of paid vacation per year."),
				chunk(1, "## Expenses\n\nMeals are reimbursed up to 70 euros per day when travelling.")));
		when(documents.findAll()).thenReturn(List.of(
				new DocumentRecord(documentId, "employee_handbook.md", "text/markdown", 100, 2, Instant.now())));
		service = service(0.3, 12_000);
	}

	@Test
	void streamsSourcesThenTokensThenDone() {
		model.respondWith("You get", " 27 days", " [1]", ".");

		List<ChatEvent> events = service.answer("how many vacation days do employees receive", List.of(), null)
			.collectList()
			.block();

		assertThat(events.getFirst()).isInstanceOf(Sources.class);
		Citation first = ((Sources) events.getFirst()).sources().getFirst();
		assertThat(first).extracting(Citation::index, Citation::documentId, Citation::filename, Citation::title)
			.containsExactly(1, documentId, "employee_handbook.md", "Employee Handbook");

		assertThat(events.subList(1, events.size() - 1)).extracting(event -> ((Token) event).text())
			.containsExactly("You get", " 27 days", " [1]", ".");

		Done done = (Done) events.getLast();
		assertThat(done.citedSources()).containsExactly(1);
		assertThat(done.notFound()).isFalse();
		assertThat(done.promptTokens()).isEqualTo(120);
		assertThat(done.completionTokens()).isEqualTo(4);
	}

	@Test
	void givesTheModelTitledNumberedSourcesAndTheRules() {
		service.answer("how many vacation days do employees receive", List.of(), 1).blockLast();

		List<Message> messages = model.lastPrompt().getInstructions();
		assertThat(messages.getFirst().getText()).contains(ChatService.NOT_FOUND_PREFIX)
			.contains("Refer to documents by their title")
			.contains("bullet points");
		assertThat(messages.getLast().getText()).startsWith("Documents: \"Employee Handbook\"")
			.contains("<source id=\"1\" document=\"Employee Handbook\">\n## Vacation")
			.doesNotContain("id=\"2\"")
			.endsWith("Message: how many vacation days do employees receive");
	}

	@Test
	void withoutMatchingPassagesTheModelStillRepliesAndKnowsTheDocumentTitles() {
		ChatService strict = service(0.99, 12_000);
		model.respondWith("I can answer questions about the Employee Handbook.");

		List<ChatEvent> events = strict.answer("what can you do?", List.of(), null).collectList().block();

		assertThat(events.getFirst()).isEqualTo(new Sources(List.of()));
		assertThat(model.lastPrompt().getInstructions().getLast().getText())
			.contains("Documents: \"Employee Handbook\"")
			.contains("Sources: none")
			.endsWith("Message: what can you do?");
		assertThat(((Done) events.getLast()).notFound()).isFalse();
	}

	@Test
	void notFoundRepliesAreFlaggedAndCiteNothing() {
		model.respondWith("I couldn't find that in", " the Employee Handbook document.", " [1]");

		Done done = (Done) service.answer("what is the dress code", List.of(), null).collectList().block().getLast();

		assertThat(done.notFound()).isTrue();
		assertThat(done.citedSources()).isEmpty();
	}

	@Test
	void withNoDocumentsAtAllItSaysSoWithoutCallingTheModel() {
		when(documents.findAll()).thenReturn(List.of());
		ChatService strict = service(0.99, 12_000);

		List<ChatEvent> events = strict.answer("hey", List.of(), null).collectList().block();

		assertThat(events).containsExactly(new Sources(List.of()), new Token(ChatService.NO_DOCUMENTS),
				new Done(List.of(), false, null, null, 0));
		assertThat(model.streamCalls()).isZero();
		assertThat(model.calls()).isZero();
	}

	@Test
	void followUpIsRewrittenForSearchAndAnsweredWithTheConversation() {
		model.callReturns("How many days of paid vacation do full-time employees receive?");
		List<Turn> history = List.of(new Turn("tell me about vacation", "You get 27 days [1]."));

		List<ChatEvent> events = service.answer("are you sure?", history, null).collectList().block();

		assertThat(model.lastCallPrompt().getInstructions().getLast().getText())
			.contains("User: tell me about vacation")
			.contains("Assistant: You get 27 days.")
			.endsWith("Latest message: are you sure?");
		Citation top = ((Sources) events.getFirst()).sources().getFirst();
		assertThat(top.text()).contains("27 days of paid vacation");

		List<Message> messages = model.lastPrompt().getInstructions();
		assertThat(messages).hasSize(4);
		assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
		assertThat(messages.get(1).getText()).isEqualTo("tell me about vacation");
		assertThat(messages.get(2)).isInstanceOf(AssistantMessage.class);
		assertThat(messages.get(2).getText()).isEqualTo("You get 27 days.");
		// The model sees what the follow-up was understood as, so "are you sure?" isn't read in isolation.
		assertThat(messages.get(3).getText()).endsWith("""
				Message: are you sure?
				This message refers to: How many days of paid vacation do full-time employees receive?""");
	}

	@Test
	void standaloneQuestionIsSentWithoutAnInterpretation() {
		service.answer("How many vacation days?", List.of(), null).collectList().block();

		assertThat(model.lastPrompt().getInstructions().getLast().getText())
			.endsWith("Message: How many vacation days?")
			.doesNotContain("This message refers to");
	}

	@Test
	void shortSmallTalkGetsAFixedReplyWithoutCallingTheModel() {
		List<ChatEvent> events = service
			.answer("Thanks!", List.of(new Turn("how many vacation days?", "27 days [1].")), null)
			.collectList()
			.block();

		assertThat(events).containsExactly(new Sources(List.of()),
				new Token("You're welcome! Ask me anything else about \"Employee Handbook\"."),
				new Done(List.of(), false, null, null, 0));
		assertThat(model.calls()).isZero();
		assertThat(model.streamCalls()).isZero();
	}

	@Test
	void greetingNamesTheDocuments() {
		Token reply = (Token) service.answer("hey", List.of(), null).collectList().block().get(1);

		assertThat(reply.text()).startsWith("Hi! I can answer questions about \"Employee Handbook\"");
	}

	@Test
	void listsDocumentTitlesReadably() {
		assertThat(ChatService.listTitles(List.of("A"))).isEqualTo("\"A\"");
		assertThat(ChatService.listTitles(List.of("Terms and Conditions", "B"))).isEqualTo("\"Terms and Conditions\" and \"B\"");
		assertThat(ChatService.listTitles(List.of("A", "B", "C"))).isEqualTo("\"A\", \"B\" and \"C\"");
		assertThat(ChatService.listTitles(List.of("A", "B", "C", "D", "E"))).isEqualTo("\"A\", \"B\", \"C\" and 2 more documents");
	}

	@Test
	void smallTalkRecognisedByTheRewriteStepAlsoSkipsTheSearch() {
		model.callReturns("SMALL_TALK");

		List<ChatEvent> events = service
			.answer("you're the best", List.of(new Turn("how many vacation days?", "27 days [1].")), null)
			.collectList()
			.block();

		assertThat(model.calls()).isEqualTo(1);
		assertThat(events.getFirst()).isEqualTo(new Sources(List.of()));
	}

	@Test
	void recognisesShortSmallTalkOnly() {
		assertThat(List.of("hi", "Hey!", "hello there", "thanks", "Thank you so much!", "ok", "got it.", "bye",
				"Good morning")).allMatch(message -> ChatService.smallTalkReply(message) != null);
		assertThat(List.of("hi, how many vacation days do I get?", "thanks, and what about sick leave?",
				"ok so what is the notice period", "great expectations", "what can you do?"))
			.allMatch(message -> ChatService.smallTalkReply(message) == null);
	}

	@Test
	void remindsTheModelToCiteNextToTheSources() {
		service.answer("vacation days?", List.of(), null).blockLast();

		assertThat(model.lastPrompt().getInstructions().getLast().getText())
			.contains("Answer using only these sources, citing them like [1] right after each statement.");
	}

	@Test
	void olderTurnsAreDroppedOnceTheHistoryBudgetIsFull() {
		// "third"+"three" and "second"+"two" fit in 20 characters; "first"+"one" doesn't.
		ChatService small = service(0.3, 20);
		List<Turn> history = List.of(new Turn("first", "one"), new Turn("second", "two"), new Turn("third", "three"));

		small.answer("vacation days?", history, null).blockLast();

		String conversation = model.lastCallPrompt().getInstructions().getLast().getText();
		assertThat(conversation).doesNotContain("first").contains("second").contains("third");
		assertThat(model.lastPrompt().getInstructions()).hasSize(1 + 4 + 1);
	}

	@Test
	void aLongConversationIsKeptWholeByDefault() {
		List<Turn> history = new ArrayList<>();
		for (int i = 1; i <= 10; i++) {
			history.add(new Turn("question " + i, "answer " + i));
		}

		service.answer("vacation days?", history, null).blockLast();

		assertThat(model.lastPrompt().getInstructions()).hasSize(1 + 20 + 1);
	}

	@Test
	void theLatestExchangeIsKeptEvenIfItAloneExceedsTheBudget() {
		ChatService tiny = service(0.3, 5);

		tiny.answer("vacation days?", List.of(new Turn("first", "one"), new Turn("a long question", "a long answer")), null)
			.blockLast();

		assertThat(model.lastPrompt().getInstructions()).hasSize(1 + 2 + 1);
	}

	@Test
	void zeroHistoryTurnsFollowUpsOff() {
		service(0.3, 0).answer("vacation days?", List.of(new Turn("first", "one")), null).blockLast();

		assertThat(model.calls()).isZero();
		assertThat(model.lastPrompt().getInstructions()).hasSize(1 + 1);
	}

	@Test
	void withoutHistoryNoRewriteCallIsMade() {
		service.answer("vacation days?", List.of(), null).blockLast();

		assertThat(model.calls()).isZero();
	}

	@Test
	void rewriteFailureFallsBackToTheQuestionAsAsked() {
		model.callFails(new IllegalStateException("model unavailable"));

		List<ChatEvent> events = service
			.answer("how many vacation days do employees receive", List.of(new Turn("hi", "Hello!")), null)
			.collectList()
			.block();

		assertThat(((Sources) events.getFirst()).sources()).isNotEmpty();
		assertThat(events.getLast()).isInstanceOf(Done.class);
	}

	@Test
	void unusableRewriteFallsBackToTheQuestionAsAsked() {
		assertThat(withRewrite("   ")).isEqualTo("are you sure?");
		assertThat(withRewrite("x".repeat(600))).isEqualTo("are you sure?");
		assertThat(withRewrite("\"Is it 27 days?\"\nExplanation: ...")).isEqualTo("Is it 27 days?");
	}

	@Test
	void sourceTextWithTemplateCharactersIsPassedThroughUnchanged() {
		vectorStore.add(List.of(chunk(2, "Vacation config example: {\"days\": 27, \"carry\": {max}} <end>")));

		service.answer("vacation config example days", List.of(), null).blockLast();

		assertThat(model.lastPrompt().getInstructions().getLast().getText())
			.contains("{\"days\": 27, \"carry\": {max}} <end>");
	}

	@Test
	void modelFailureEndsTheStreamWithAnErrorEvent() {
		model.respondWith("Partial").failAfterTokens(new IllegalStateException("connection reset"));

		List<ChatEvent> events = service.answer("vacation days", List.of(), null).collectList().block();

		assertThat(events).hasSize(3);
		assertThat(events.get(1)).isEqualTo(new Token("Partial"));
		assertThat(events.getLast()).isInstanceOf(ChatEvent.Error.class);
		assertThat(((ChatEvent.Error) events.getLast()).message()).doesNotContain("connection reset");
	}

	@Test
	void citedSourcesAreDedupedOrderedAndLimitedToRealSources() {
		assertThat(ChatService.citedSources("A [2]. B [1][2]. C [9]. D [0]. E [x].", 3)).containsExactly(2, 1);
		assertThat(ChatService.citedSources("No citations here.", 3)).isEmpty();
		assertThat(ChatService.citedSources(" I couldn't find that in the Handbook. [1]", 3)).isEmpty();
	}

	@Test
	void aRefusalThatExplainsWhatTheDocumentsSayIsAPartialAnswer() {
		String partial = "I couldn't find that in the Handbook. It doesn't rank its policies, but it stresses reporting [2].";

		assertThat(ChatService.isNotFound(partial)).isFalse();
		assertThat(ChatService.citedSources(partial, 3)).containsExactly(2);
		assertThat(ChatService.isNotFound("I couldn't find that in the Handbook.")).isTrue();
		assertThat(ChatService.isNotFound("I couldn't find that in the Handbook. [1]")).isTrue();
		assertThat(ChatService.isNotFound("I couldn't find that in the \"Handbook\" document")).isTrue();
	}

	private String withRewrite(String reply) {
		model.callReturns(reply);
		return service.standaloneQuestion("are you sure?", List.of(new Turn("q", "a")));
	}

	private ChatService service(double threshold, int historyChars) {
		return new ChatService(ChatClient.builder(model), vectorStore, documents,
				new ChatProperties(5, threshold, historyChars));
	}

	private Document chunk(int index, String text) {
		return new Document(text, Map.of(IngestionService.DOCUMENT_ID, documentId.toString(),
				IngestionService.FILENAME, "employee_handbook.md", IngestionService.CHUNK_INDEX, index));
	}

}
