package com.groundedkit.docbound.chat;

import java.util.List;
import java.util.UUID;

import com.groundedkit.docbound.chat.ChatEvent.Citation;
import com.groundedkit.docbound.chat.ChatService.Turn;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

	@Autowired
	private MockMvcTester mvc;

	@MockitoBean
	private ChatService chat;

	@Test
	void streamsEventsAsServerSentEvents() throws Exception {
		UUID documentId = UUID.randomUUID();
		when(chat.answer(eq("How many vacation days?"), any(), any())).thenReturn(Flux.just(
				new ChatEvent.Sources(List.of(new Citation(1, documentId, "handbook.md", "Handbook", 0, 0.71, "27 days"))),
				new ChatEvent.Token("27 days [1]."), new ChatEvent.Done(List.of(1), false, 120, 4, 950)));

		MvcTestResult result = post("{\"question\": \"How many vacation days?\"}");

		assertThat(result).hasStatusOk();
		assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
		String body = result.getResponse().getContentAsString();
		assertThat(body).contains("event:sources\ndata:{\"sources\":[{\"index\":1,\"documentId\":\"" + documentId)
			.contains("\"title\":\"Handbook\"")
			.contains("event:token\ndata:{\"text\":\"27 days [1].\"}")
			.contains("event:done\ndata:{\"citedSources\":[1],\"notFound\":false,\"promptTokens\":120");
		assertThat(body.indexOf("event:sources")).isLessThan(body.indexOf("event:token"));
		assertThat(body.indexOf("event:token")).isLessThan(body.indexOf("event:done"));
	}

	@Test
	void passesConversationHistoryToTheService() {
		when(chat.answer(any(), any(), any())).thenReturn(Flux.empty());

		assertThat(post("""
				{"question": "are you sure?",
				 "history": [{"question": "what is the policy about?", "answer": "It covers conduct [1]."}]}
				""")).hasStatusOk();

		verify(chat).answer("are you sure?", List.of(new Turn("what is the policy about?", "It covers conduct [1].")),
				null);
	}

	@Test
	void missingHistoryIsTreatedAsEmpty() {
		when(chat.answer(any(), any(), any())).thenReturn(Flux.empty());

		post("{\"question\": \"hey\"}");

		verify(chat).answer("hey", List.of(), null);
	}

	@Test
	void rejectsBlankQuestion() {
		assertThat(post("{\"question\": \"  \"}")).hasStatus(HttpStatus.BAD_REQUEST);
		verifyNoInteractions(chat);
	}

	@Test
	void rejectsOverlongQuestion() {
		assertThat(post("{\"question\": \"" + "a".repeat(2001) + "\"}")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void rejectsOutOfRangeTopK() {
		assertThat(post("{\"question\": \"vacation\", \"topK\": 50}")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void rejectsTooMuchOrInvalidHistory() {
		String turn = "{\"question\": \"q\", \"answer\": \"a\"}";
		String tooMany = String.join(",", java.util.Collections.nCopies(21, turn));
		assertThat(post("{\"question\": \"q\", \"history\": [" + tooMany + "]}")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(post("{\"question\": \"q\", \"history\": [{\"question\": \"\", \"answer\": \"a\"}]}"))
			.hasStatus(HttpStatus.BAD_REQUEST);
		verifyNoInteractions(chat);
	}

	private MvcTestResult post(String json) {
		return mvc.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON).content(json).exchange();
	}

}
