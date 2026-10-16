package com.groundedkit.docbound.ingestion;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentRepository {

	private static final String SELECT = """
			SELECT id, filename, content_type, size_bytes, chunk_count, created_at FROM documents
			""";

	private final JdbcClient jdbc;

	public DocumentRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(DocumentRecord document) {
		jdbc.sql("""
				INSERT INTO documents (id, filename, content_type, size_bytes, chunk_count, created_at)
				VALUES (:id, :filename, :contentType, :sizeBytes, :chunkCount, :createdAt)
				""")
			.param("id", document.id())
			.param("filename", document.filename())
			.param("contentType", document.contentType())
			.param("sizeBytes", document.sizeBytes())
			.param("chunkCount", document.chunkCount())
			.param("createdAt", Timestamp.from(document.createdAt()))
			.update();
	}

	public List<DocumentRecord> findAll() {
		return jdbc.sql(SELECT + "ORDER BY created_at DESC").query(this::map).list();
	}

	public Optional<DocumentRecord> findById(UUID id) {
		return jdbc.sql(SELECT + "WHERE id = :id").param("id", id).query(this::map).optional();
	}

	/** @return {@code false} if the document no longer exists (e.g. it was deleted while being summarized) */
	public boolean saveSummary(UUID id, String summary) {
		return jdbc.sql("UPDATE documents SET summary = :summary WHERE id = :id")
			.param("summary", summary)
			.param("id", id)
			.update() > 0;
	}

	/** Documents uploaded before summaries existed, or whose summary failed (e.g. Ollama was down). */
	public List<DocumentRecord> findWithoutSummary() {
		return jdbc.sql(SELECT + "WHERE summary IS NULL ORDER BY created_at").query(this::map).list();
	}

	/** @return {@code true} if a row was deleted */
	public boolean deleteById(UUID id) {
		return jdbc.sql("DELETE FROM documents WHERE id = :id").param("id", id).update() > 0;
	}

	private DocumentRecord map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new DocumentRecord(
				rs.getObject("id", UUID.class),
				rs.getString("filename"),
				rs.getString("content_type"),
				rs.getLong("size_bytes"),
				rs.getInt("chunk_count"),
				rs.getTimestamp("created_at").toInstant());
	}

}
