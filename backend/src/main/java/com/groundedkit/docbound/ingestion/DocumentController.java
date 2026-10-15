package com.groundedkit.docbound.ingestion;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
class DocumentController {

	private final IngestionService ingestion;

	DocumentController(IngestionService ingestion) {
		this.ingestion = ingestion;
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<DocumentRecord> upload(@RequestParam("file") MultipartFile file) {
		DocumentRecord document = ingestion.ingest(file);
		return ResponseEntity.created(URI.create("/api/documents/" + document.id())).body(document);
	}

	@GetMapping
	List<DocumentRecord> list() {
		return ingestion.list();
	}

	@GetMapping("/{id}")
	DocumentRecord get(@PathVariable UUID id) {
		return ingestion.get(id);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable UUID id) {
		ingestion.delete(id);
	}

}
