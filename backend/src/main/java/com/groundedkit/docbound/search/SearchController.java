package com.groundedkit.docbound.search;

import java.util.List;
import java.util.UUID;

import com.groundedkit.docbound.ingestion.IngestionService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Raw semantic search over ingested chunks. Useful for checking retrieval quality before the chat layer
 * is involved: if the right chunks don't show up here, the LLM won't see them either.
 */
@RestController
class SearchController {

	private final VectorStore vectorStore;

	SearchController(VectorStore vectorStore) {
		this.vectorStore = vectorStore;
	}

	@GetMapping("/api/search")
	List<SearchResult> search(@RequestParam @NotBlank String q,
			@RequestParam(defaultValue = "5") @Min(1) @Max(20) int topK) {
		return vectorStore.similaritySearch(SearchRequest.builder().query(q).topK(topK).build())
			.stream()
			.map(SearchResult::from)
			.toList();
	}

	record SearchResult(UUID documentId, String filename, int chunkIndex, double score, String text) {

		static SearchResult from(Document chunk) {
			var metadata = chunk.getMetadata();
			return new SearchResult(
					UUID.fromString(String.valueOf(metadata.get(IngestionService.DOCUMENT_ID))),
					String.valueOf(metadata.get(IngestionService.FILENAME)),
					((Number) metadata.get(IngestionService.CHUNK_INDEX)).intValue(),
					chunk.getScore() == null ? 0 : chunk.getScore(),
					chunk.getText());
		}

	}

}
