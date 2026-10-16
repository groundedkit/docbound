package com.groundedkit.docbound.ingestion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.groundedkit.docbound.ingestion.IngestionExceptions.DocumentNotFoundException;
import com.groundedkit.docbound.ingestion.IngestionExceptions.UnreadableDocumentException;
import com.groundedkit.docbound.ingestion.IngestionExceptions.UnsupportedFileTypeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Upload pipeline: parse (Tika) → split along headings and sentences ({@link StructureAwareTextSplitter}) → embed
 * → store in pgvector.
 * <p>
 * Every chunk carries {@link #DOCUMENT_ID}, {@link #FILENAME} and {@link #CHUNK_INDEX} metadata, so search
 * results can be traced back to their source and a document's chunks can be deleted together.
 */
@Service
public class IngestionService {

	public static final String DOCUMENT_ID = "document_id";

	public static final String FILENAME = "filename";

	public static final String CHUNK_INDEX = "chunk_index";

	private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

	private final VectorStore vectorStore;

	private final DocumentRepository documents;

	private final IngestionProperties properties;

	private final StructureAwareTextSplitter splitter;

	private final ApplicationEventPublisher events;

	public IngestionService(VectorStore vectorStore, DocumentRepository documents, IngestionProperties properties,
			ApplicationEventPublisher events) {
		this.vectorStore = vectorStore;
		this.documents = documents;
		this.properties = properties;
		this.events = events;
		this.splitter = new StructureAwareTextSplitter(properties.chunkSize(), properties.chunkOverlap(),
				properties.paragraphBreakTokens());
	}

	/**
	 * Ingests a file in one transaction: if embedding fails, the {@code documents} row is rolled back too.
	 * Embedding happens synchronously, so very large files make the request slow; the upload size limit
	 * ({@code spring.servlet.multipart.max-file-size}) keeps this bounded.
	 */
	@Transactional
	public DocumentRecord ingest(MultipartFile file) {
		String filename = StringUtils.cleanPath(StringUtils.hasText(file.getOriginalFilename())
				? file.getOriginalFilename() : "upload");
		String extension = extensionOf(filename);
		if (!properties.allowedExtensions().contains(extension)) {
			throw new UnsupportedFileTypeException(extension, properties.allowedExtensions());
		}
		if (file.isEmpty()) {
			throw new UnreadableDocumentException("'%s' is empty".formatted(filename));
		}

		List<Document> pages = read(file, filename);
		List<Document> chunks = splitter.apply(pages);
		if (chunks.isEmpty()) {
			throw new UnreadableDocumentException(
					"No text could be extracted from '%s'. Scanned PDFs need OCR before upload.".formatted(filename));
		}

		UUID documentId = UUID.randomUUID();
		List<Document> tagged = new ArrayList<>(chunks.size());
		for (int i = 0; i < chunks.size(); i++) {
			Map<String, Object> metadata = new HashMap<>(chunks.get(i).getMetadata());
			metadata.put(DOCUMENT_ID, documentId.toString());
			metadata.put(FILENAME, filename);
			metadata.put(CHUNK_INDEX, i);
			tagged.add(Document.builder().text(chunks.get(i).getText()).metadata(metadata).build());
		}

		DocumentRecord record = new DocumentRecord(documentId, filename, file.getContentType(), file.getSize(),
				tagged.size(), Instant.now());
		documents.insert(record);
		vectorStore.add(tagged);
		log.info("Ingested '{}' as {} ({} chunks)", filename, documentId, tagged.size());
		events.publishEvent(new DocumentIngested(documentId, filename, tagged.stream().map(Document::getText).toList()));
		return record;
	}

	public List<DocumentRecord> list() {
		return documents.findAll();
	}

	public DocumentRecord get(UUID id) {
		return documents.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
	}

	@Transactional
	public void delete(UUID id) {
		if (!documents.deleteById(id)) {
			throw new DocumentNotFoundException(id);
		}
		vectorStore.delete(new FilterExpressionBuilder().eq(DOCUMENT_ID, id.toString()).build());
		log.info("Deleted document {}", id);
	}

	private List<Document> read(MultipartFile file, String filename) {
		byte[] bytes;
		try {
			bytes = file.getBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		var resource = new ByteArrayResource(bytes) {
			@Override
			public String getFilename() {
				return filename;
			}
		};
		try {
			return new TikaDocumentReader(resource).get()
				.stream()
				.filter(page -> StringUtils.hasText(page.getText()))
				.toList();
		}
		catch (RuntimeException ex) {
			throw new UnreadableDocumentException("'%s' could not be parsed".formatted(filename), ex);
		}
	}

	private static String extensionOf(String filename) {
		String extension = StringUtils.getFilenameExtension(filename);
		return extension == null ? "" : extension.toLowerCase(Locale.ROOT);
	}

}
