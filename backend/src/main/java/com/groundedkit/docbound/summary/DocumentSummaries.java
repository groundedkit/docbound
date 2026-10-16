package com.groundedkit.docbound.summary;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.groundedkit.docbound.chat.DocumentTitles;
import com.groundedkit.docbound.ingestion.DocumentIngested;
import com.groundedkit.docbound.ingestion.DocumentRecord;
import com.groundedkit.docbound.ingestion.DocumentRepository;
import com.groundedkit.docbound.ingestion.IngestionService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Keeps a summary of every document, so questions about a whole document ("what is this about?", "summarize it")
 * can be answered from all of it instead of the five passages most similar to the question (issue #5).
 * <p>
 * The summary is stored on the document and also added to the vector store as one more passage, tagged
 * {@link #KIND} = {@value #SUMMARY} with {@link IngestionService#CHUNK_INDEX} -1. Overview questions then retrieve it
 * like any other source, and it's cited the same way. Summaries are written in the background, one at a time, after
 * the upload has committed, so uploads stay fast; documents without one (older uploads, or Ollama was down) are
 * summarized when the application starts.
 */
@Service
public class DocumentSummaries {

	/** Metadata key that marks non-chunk passages in the vector store. */
	public static final String KIND = "kind";

	public static final String SUMMARY = "summary";

	/** {@link IngestionService#CHUNK_INDEX} of a summary passage. */
	public static final int SUMMARY_INDEX = -1;

	private static final Logger log = LoggerFactory.getLogger(DocumentSummaries.class);

	private final DocumentSummarizer summarizer;

	private final DocumentRepository documents;

	private final VectorStore vectorStore;

	private final JdbcClient jdbc;

	private final SummaryProperties properties;

	/** One summary at a time: they compete with chat for the same local model. */
	private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("summaries").factory());

	public DocumentSummaries(DocumentSummarizer summarizer, DocumentRepository documents, VectorStore vectorStore,
			JdbcClient jdbc, SummaryProperties properties) {
		this.summarizer = summarizer;
		this.documents = documents;
		this.vectorStore = vectorStore;
		this.jdbc = jdbc;
		this.properties = properties;
	}

	@TransactionalEventListener
	void onIngested(DocumentIngested event) {
		if (properties.enabled()) {
			executor.execute(() -> summarize(event.id(), event.filename(), event.chunks()));
		}
	}

	@EventListener(ApplicationReadyEvent.class)
	void onStartup() {
		if (properties.enabled()) {
			summarizeMissing();
		}
	}

	/** Queues a summary for every document that doesn't have one yet. */
	public void summarizeMissing() {
		List<DocumentRecord> missing = documents.findWithoutSummary();
		if (!missing.isEmpty()) {
			log.info("Summarizing {} document(s) uploaded without a summary", missing.size());
		}
		for (DocumentRecord document : missing) {
			executor.execute(() -> summarize(document.id(), document.filename(), chunks(document.id())));
		}
	}

	/** Writes and stores one document's summary. Failures are logged and retried at the next start. */
	void summarize(UUID id, String filename, List<String> chunks) {
		try {
			long start = System.nanoTime();
			String title = DocumentTitles.fromFilename(filename);
			String summary = summarizer.summarize(title, chunks);
			if (summary.isBlank()) {
				log.warn("The model returned an empty summary for '{}'", filename);
				return;
			}
			if (!documents.saveSummary(id, summary)) {
				return;
			}
			Map<String, Object> metadata = new HashMap<>();
			metadata.put(IngestionService.DOCUMENT_ID, id.toString());
			metadata.put(IngestionService.FILENAME, filename);
			metadata.put(IngestionService.CHUNK_INDEX, SUMMARY_INDEX);
			metadata.put(KIND, SUMMARY);
			vectorStore.add(List.of(Document.builder()
				.text("Summary of the %s document:%n%n%s".formatted(title, summary))
				.metadata(metadata)
				.build()));
			if (documents.findById(id).isEmpty()) {
				// Deleted while the summary was being written: don't leave its passage behind.
				vectorStore.delete(new FilterExpressionBuilder().eq(IngestionService.DOCUMENT_ID, id.toString()).build());
				return;
			}
			log.info("Summarized '{}' in {} s", filename, (System.nanoTime() - start) / 1_000_000_000);
		}
		catch (RuntimeException ex) {
			log.warn("Could not summarize '{}'; it will be retried at the next start", filename, ex);
		}
	}

	/** A stored document's chunks in order, read back from the vector store. */
	private List<String> chunks(UUID id) {
		return jdbc.sql("""
				SELECT content FROM vector_store
				WHERE metadata ->> 'document_id' = :id AND metadata ->> 'kind' IS NULL
				ORDER BY (metadata ->> 'chunk_index')::int
				""").param("id", id.toString()).query(String.class).list();
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

}
