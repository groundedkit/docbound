package com.groundedkit.docbound.ingestion;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning knobs for the ingestion pipeline ({@code app.ingestion.*}).
 *
 * @param chunkSize         maximum chunk size in tokens; smaller chunks give more precise retrieval, larger ones more
 *                          context per hit. The default was chosen with {@code RetrievalEval}.
 * @param chunkOverlap      tokens of trailing sentences repeated at the start of the next chunk of the same section,
 *                          so answers that fall on a chunk boundary stay retrievable; must be less than {@code chunkSize}
 * @param paragraphBreakTokens once a chunk holds this many tokens it ends at the next paragraph, so chunks of
 *                          heading-less prose stay on one topic. The default was chosen with {@code RetrievalEval}.
 * @param allowedExtensions file extensions accepted for upload (lower case, without the dot)
 */
@ConfigurationProperties("app.ingestion")
public record IngestionProperties(
		@DefaultValue("400") int chunkSize,
		@DefaultValue("100") int chunkOverlap,
		@DefaultValue("60") int paragraphBreakTokens,
		@DefaultValue({ "pdf", "md", "markdown", "txt", "docx", "html" }) List<String> allowedExtensions) {
}
