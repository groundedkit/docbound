package com.groundedkit.docbound.ingestion;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.groundedkit.docbound.ingestion.IngestionExceptions.DocumentNotFoundException;
import com.groundedkit.docbound.ingestion.IngestionExceptions.UnsupportedFileTypeException;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

	@Autowired
	private MockMvcTester mvc;

	@MockitoBean
	private IngestionService ingestion;

	private final MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain",
			"hello".getBytes(StandardCharsets.UTF_8));

	@Test
	void uploadReturnsCreatedDocument() {
		UUID id = UUID.randomUUID();
		when(ingestion.ingest(any())).thenReturn(
				new DocumentRecord(id, "notes.txt", "text/plain", 5, 1, Instant.parse("2026-01-01T00:00:00Z")));

		var result = mvc.post().uri("/api/documents").multipart().file(file);

		assertThat(result).hasStatus(HttpStatus.CREATED).hasHeader("Location", "/api/documents/" + id);
		assertThat(result).bodyJson().extractingPath("$.filename").isEqualTo("notes.txt");
		assertThat(result).bodyJson().extractingPath("$.createdAt").isEqualTo("2026-01-01T00:00:00Z");
	}

	@Test
	void uploadWithoutFilePartIsBadRequest() {
		assertThat(mvc.post().uri("/api/documents").multipart()).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void unsupportedFileTypeIsReportedAsProblem() {
		when(ingestion.ingest(any())).thenThrow(new UnsupportedFileTypeException("exe", List.of("pdf", "txt")));

		var result = mvc.post().uri("/api/documents").multipart().file(file);

		assertThat(result).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
			.hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("Allowed types: pdf, txt");
	}

	@Test
	void unknownDocumentIsNotFound() {
		UUID id = UUID.randomUUID();
		when(ingestion.get(id)).thenThrow(new DocumentNotFoundException(id));

		assertThat(mvc.get().uri("/api/documents/{id}", id)).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void malformedIdIsBadRequest() {
		assertThat(mvc.get().uri("/api/documents/not-a-uuid")).hasStatus(HttpStatus.BAD_REQUEST);
	}

}
