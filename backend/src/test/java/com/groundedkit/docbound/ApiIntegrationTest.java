package com.groundedkit.docbound;

import java.nio.charset.StandardCharsets;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.groundedkit.docbound.summary.DocumentSummaries;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full pipeline against a real pgvector database (Flyway schema, PgVectorStore, metadata filters, chat).
 * Embeddings and answers come from {@link FakeEmbeddingModel} and {@link FakeChatModel}, so Ollama isn't
 * needed. Skipped when Docker isn't running.
 */
// Summaries are triggered explicitly (summarizeMissing), so background calls don't mix with scripted chat replies.
@SpringBootTest(properties = { "spring.ai.model.embedding=none", "spring.ai.model.chat=none",
		"spring.ai.ollama.init.pull-model-strategy=never", "app.summaries.enabled=false" })
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@Import(ApiIntegrationTest.Config.class)
class ApiIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class Config {

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(
					DockerImageName.parse("pgvector/pgvector:0.8.7-pg17").asCompatibleSubstituteFor("postgres"));
		}

		@Bean
		EmbeddingModel embeddingModel() {
			return new FakeEmbeddingModel();
		}

		@Bean
		FakeChatModel chatModel() {
			return new FakeChatModel();
		}

	}

	@Autowired
	private MockMvcTester mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private FakeChatModel chatModel;

	@Autowired
	private DocumentSummaries summaries;

	@Test
	void summaryIsStoredAsASearchablePassageAndDeletedWithTheDocument() throws Exception {
		var file = new MockMultipartFile("file", "leave-policy.md", "text/markdown", """
				# Parental leave
				Parents receive 16 weeks of paid leave.
				""".getBytes(StandardCharsets.UTF_8));
		var upload = mvc.post().uri("/api/documents").multipart().file(file).exchange();
		String id = JsonPath.read(upload.getResponse().getContentAsString(), "$.id");

		summaries.summarizeMissing();

		String summary = null;
		for (int i = 0; i < 100 && summary == null; i++) {
			Thread.sleep(50);
			summary = jdbc.sql("SELECT summary FROM documents WHERE id = :id").param("id", java.util.UUID.fromString(id))
				.query(String.class).optional().orElse(null);
		}
		// FakeChatModel echoes its input, so the "summary" contains the document text.
		assertThat(summary).contains("Parents receive 16 weeks of paid leave.");
		String passage = jdbc.sql("""
				SELECT content FROM vector_store
				WHERE metadata ->> 'document_id' = :id AND metadata ->> 'kind' = 'summary'
				  AND (metadata ->> 'chunk_index')::int = -1
				""").param("id", id).query(String.class).single();
		assertThat(passage).startsWith("Summary of the Leave Policy document:");

		mvc.delete().uri("/api/documents/{id}", id).exchange();
		assertThat(jdbc.sql("SELECT count(*) FROM vector_store WHERE metadata ->> 'document_id' = :id")
			.param("id", id).query(Integer.class).single()).isZero();
	}

	@Test
	void uploadSearchAndDelete() throws Exception {
		var file = new MockMultipartFile("file", "k8s.md", "text/markdown", """
				# Pod restart policy
				Kubernetes restarts containers according to the pod restart policy: Always, OnFailure or Never.
				""".getBytes(StandardCharsets.UTF_8));
		var upload = mvc.post().uri("/api/documents").multipart().file(file).exchange();
		assertThat(upload).hasStatus(HttpStatus.CREATED);
		String id = JsonPath.read(upload.getResponse().getContentAsString(), "$.id");

		assertThat(chunkCount(id)).isPositive();
		assertThat(mvc.get().uri("/api/documents")).bodyJson().extractingPath("$[0].id").isEqualTo(id);

		var search = mvc.get().uri("/api/search").param("q", "pod restart policy").exchange();
		assertThat(search).hasStatusOk();
		assertThat(search).bodyJson().extractingPath("$[0].documentId").isEqualTo(id);
		assertThat(search).bodyJson().extractingPath("$[0].filename").isEqualTo("k8s.md");

		assertThat(mvc.delete().uri("/api/documents/{id}", id)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(chunkCount(id)).isZero();
		assertThat(mvc.get().uri("/api/documents/{id}", id)).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void chatStreamsAnAnswerCitingTheUploadedDocument() throws Exception {
		var file = new MockMultipartFile("file", "handbook.md", "text/markdown", """
				# Vacation
				Full-time employees receive 27 days of paid vacation per calendar year.
				""".getBytes(StandardCharsets.UTF_8));
		var upload = mvc.post().uri("/api/documents").multipart().file(file).exchange();
		String id = JsonPath.read(upload.getResponse().getContentAsString(), "$.id");
		chatModel.respondWith("You get 27 days", " [1].");

		var chat = mvc.post()
			.uri("/api/chat")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"question\": \"How many days of paid vacation do full-time employees receive?\"}")
			.exchange();

		assertThat(chat).hasStatusOk();
		String events = chat.getResponse().getContentAsString();
		assertThat(events).contains("event:sources").contains("\"documentId\":\"" + id + "\"")
			.contains("\"filename\":\"handbook.md\"")
			.contains("event:token\ndata:{\"text\":\"You get 27 days\"}")
			.contains("event:done\ndata:{\"citedSources\":[1]");
		assertThat(chatModel.lastPrompt().getInstructions().getLast().getText())
			.contains("<source id=\"1\" document=\"Handbook\">")
			.contains("27 days of paid vacation");
	}

	@Test
	void searchValidatesParameters() {
		assertThat(mvc.get().uri("/api/search").param("q", "x").param("topK", "500"))
			.hasStatus(HttpStatus.BAD_REQUEST);
	}

	private long chunkCount(String documentId) {
		return jdbc.sql("SELECT count(*) FROM vector_store WHERE metadata ->> 'document_id' = :id")
			.param("id", documentId)
			.query(Long.class)
			.single();
	}

}
