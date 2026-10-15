package com.groundedkit.docbound.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTitlesTest {

	@Test
	void turnsFilenamesIntoReadableTitles() {
		assertThat(DocumentTitles.fromFilename("policy_workplace_concerns.pdf")).isEqualTo("Policy Workplace Concerns");
		assertThat(DocumentTitles.fromFilename("incident-runbook.txt")).isEqualTo("Incident Runbook");
		assertThat(DocumentTitles.fromFilename("Q3 report.final.docx")).isEqualTo("Q3 Report Final");
	}

	@Test
	void keepsWordsThatAreAlreadyCapitalised() {
		assertThat(DocumentTitles.fromFilename("API-Guide_iOS.md")).isEqualTo("API Guide iOS");
	}

	@Test
	void handlesNamesWithoutAnExtensionOrWords() {
		assertThat(DocumentTitles.fromFilename("README")).isEqualTo("README");
		assertThat(DocumentTitles.fromFilename(".pdf")).isEqualTo("Pdf");
		assertThat(DocumentTitles.fromFilename("___.pdf")).isEqualTo("___.pdf");
	}

}
