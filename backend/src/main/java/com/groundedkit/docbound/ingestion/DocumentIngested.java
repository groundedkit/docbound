package com.groundedkit.docbound.ingestion;

import java.util.List;
import java.util.UUID;

/** Published inside the upload transaction once a document's chunks are stored; {@code chunks} are in order. */
public record DocumentIngested(UUID id, String filename, List<String> chunks) {
}
