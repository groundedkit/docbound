package com.groundedkit.docbound.summary;

import java.util.List;

import com.groundedkit.docbound.FakeChatModel;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSummarizerTest {

	private final FakeChatModel model = new FakeChatModel();

	@Test
	void shortDocumentIsSummarizedInOneCall() {
		DocumentSummarizer summarizer = new DocumentSummarizer(ChatClient.builder(model), new SummaryProperties(true, 1000));

		String summary = summarizer.summarize("Handbook", List.of("Vacation is 27 days.", "Remote work is allowed."));

		assertThat(model.calls()).isEqualTo(1);
		assertThat(model.lastCallPrompt().getInstructions().getFirst().getText()).isEqualTo(DocumentSummarizer.SUMMARY_PROMPT);
		assertThat(model.lastCallPrompt().getUserMessage().getText()).startsWith("Document: Handbook\n\n");
		// The fake model echoes its input; the echoed "Document:" line is removed from the summary.
		assertThat(summary).isEqualTo("Vacation is 27 days.\n\nRemote work is allowed.");
	}

	@Test
	void longDocumentIsSummarizedSectionBySectionSoTheEndIsCoveredToo() {
		DocumentSummarizer summarizer = new DocumentSummarizer(ChatClient.builder(model), new SummaryProperties(true, 40));

		String summary = summarizer.summarize("Handbook",
				List.of("Vacation is 27 days per year.", "Remote work needs approval.", "Laptops are returned on leaving."));

		// Three sections of notes, then one call that combines them.
		assertThat(model.calls()).isEqualTo(4);
		assertThat(summary).contains("part 1 of 3").contains("part 3 of 3").contains("Laptops are returned on leaving.");
		assertThat(model.lastCallPrompt().getInstructions().getFirst().getText()).isEqualTo(DocumentSummarizer.SUMMARY_PROMPT);
	}

	@Test
	void sectionsKeepChunksTogetherUpToTheLimit() {
		assertThat(DocumentSummarizer.sections(List.of("aaaa", "bbbb", "cccc"), 10)).containsExactly("aaaa\n\nbbbb", "cccc");
		assertThat(DocumentSummarizer.sections(List.of("a very long single chunk"), 5)).containsExactly("a very long single chunk");
		assertThat(DocumentSummarizer.sections(List.of(), 5)).isEmpty();
	}

}
