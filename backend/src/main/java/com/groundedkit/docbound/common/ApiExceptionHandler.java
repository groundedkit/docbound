package com.groundedkit.docbound.common;

import com.groundedkit.docbound.ingestion.IngestionExceptions.DocumentNotFoundException;
import com.groundedkit.docbound.ingestion.IngestionExceptions.UnreadableDocumentException;
import com.groundedkit.docbound.ingestion.IngestionExceptions.UnsupportedFileTypeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps errors to RFC 9457 problem responses. The base class already covers framework errors such as
 * validation failures, missing request parts and oversized uploads (413).
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler
	ProblemDetail handle(UnsupportedFileTypeException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail handle(UnreadableDocumentException ex) {
		if (ex.getCause() != null) {
			log.warn(ex.getMessage(), ex.getCause());
		}
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
	}

	@ExceptionHandler
	ProblemDetail handle(DocumentNotFoundException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

}
