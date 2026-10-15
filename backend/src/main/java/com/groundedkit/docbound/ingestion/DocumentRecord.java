package com.groundedkit.docbound.ingestion;

import java.time.Instant;
import java.util.UUID;

/** An uploaded file and how many chunks it was split into. */
public record DocumentRecord(
		UUID id,
		String filename,
		String contentType,
		long sizeBytes,
		int chunkCount,
		Instant createdAt) {
}
