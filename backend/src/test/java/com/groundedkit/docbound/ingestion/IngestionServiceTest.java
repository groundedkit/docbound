package com.groundedkit.docbound.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import com.groundedkit.docbound.FakeEmbeddingModel;
import com.groundedkit.docbound.ingestion.IngestionExceptions.DocumentNotFoundException;
import com.groundedkit.docbound.ingestion.IngestionExceptions.UnreadableDocumentException;
import com.groundedkit.docbound.ingestion.IngestionExceptions.UnsupportedFileTypeException;
import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Runs the real Tika parsing and token splitting against an in-memory vector store. No Docker needed. */
class IngestionServiceTest {

	private final VectorStore vectorStore = SimpleVectorStore.builder(new FakeEmbeddingModel()).build();

	private final DocumentRepository repository = mock(DocumentRepository.class);

	private final IngestionService service = new IngestionService(vectorStore, repository,
			new IngestionProperties(100, 20, 60, List.of("txt", "md", "pdf")));

	@Test
	void splitsFileIntoChunksTaggedWithTheirSource() {
		String text = IntStream.range(0, 40)
			.mapToObj(i -> "Sentence number %d explains how the starter turns uploaded files into searchable chunks."
				.formatted(i))
			.reduce("", (a, b) -> a + b + " ");

		DocumentRecord record = service.ingest(textFile("guide.txt", text));

		assertThat(record.filename()).isEqualTo("guide.txt");
		assertThat(record.chunkCount()).isGreaterThan(1);
		verify(repository).insert(record);

		List<Document> chunks = allChunks();
		assertThat(chunks).hasSize(record.chunkCount()).allSatisfy(chunk -> {
			assertThat(chunk.getMetadata()).containsEntry(IngestionService.DOCUMENT_ID, record.id().toString())
				.containsEntry(IngestionService.FILENAME, "guide.txt");
		});
		assertThat(chunks).extracting(chunk -> chunk.getMetadata().get(IngestionService.CHUNK_INDEX))
			.containsExactlyInAnyOrderElementsOf(IntStream.range(0, record.chunkCount()).boxed().toList());
	}

	@Test
	void rejectsUnsupportedFileType() {
		assertThatThrownBy(() -> service.ingest(textFile("payload.exe", "MZ")))
			.isInstanceOf(UnsupportedFileTypeException.class)
			.hasMessageContaining("exe");
		verifyNoInteractions(repository);
	}

	@Test
	void rejectsFileWithoutExtension() {
		assertThatThrownBy(() -> service.ingest(textFile("README", "hello")))
			.isInstanceOf(UnsupportedFileTypeException.class);
	}

	@Test
	void rejectsEmptyFile() {
		assertThatThrownBy(() -> service.ingest(textFile("empty.txt", "")))
			.isInstanceOf(UnreadableDocumentException.class);
		verifyNoInteractions(repository);
	}

	@Test
	void rejectsFileWithNoExtractableText() {
		assertThatThrownBy(() -> service.ingest(textFile("blank.txt", "   \n\n   ")))
			.isInstanceOf(UnreadableDocumentException.class)
			.hasMessageContaining("No text could be extracted");
		verifyNoInteractions(repository);
	}

	@Test
	void deleteRemovesTheDocumentsChunksOnly() {
		DocumentRecord keep = service.ingest(textFile("keep.md", "# Keep\nThis document stays in the index."));
		DocumentRecord remove = service.ingest(textFile("remove.md", "# Remove\nThis document gets deleted."));
		when(repository.deleteById(remove.id())).thenReturn(true);

		service.delete(remove.id());

		assertThat(allChunks()).isNotEmpty()
			.allSatisfy(chunk -> assertThat(chunk.getMetadata())
				.containsEntry(IngestionService.DOCUMENT_ID, keep.id().toString()));
	}

	@Test
	void deleteOfUnknownDocumentFails() {
		UUID id = UUID.randomUUID();
		assertThatThrownBy(() -> service.delete(id)).isInstanceOf(DocumentNotFoundException.class);
	}

	private List<Document> allChunks() {
		return vectorStore.similaritySearch(
				SearchRequest.builder().query("document chunks").topK(1000).similarityThresholdAll().build());
	}

	private static MockMultipartFile textFile(String name, String content) {
		return new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
	}

}
