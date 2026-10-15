package com.groundedkit.docbound.ingestion;

import java.util.List;
import java.util.UUID;

/** Errors the ingestion API reports to clients. Mapped to HTTP responses in {@code ApiExceptionHandler}. */
public final class IngestionExceptions {

	private IngestionExceptions() {
	}

	public static class UnsupportedFileTypeException extends RuntimeException {

		public UnsupportedFileTypeException(String extension, List<String> allowed) {
			super("File type '%s' is not supported. Allowed types: %s"
				.formatted(extension.isEmpty() ? "(none)" : extension, String.join(", ", allowed)));
		}

	}

	/** The file parsed, but contained no text to index (e.g. a scanned PDF without OCR), or could not be parsed. */
	public static class UnreadableDocumentException extends RuntimeException {

		public UnreadableDocumentException(String message) {
			super(message);
		}

		public UnreadableDocumentException(String message, Throwable cause) {
			super(message, cause);
		}

	}

	public static class DocumentNotFoundException extends RuntimeException {

		public DocumentNotFoundException(UUID id) {
			super("Document " + id + " not found");
		}

	}

}
