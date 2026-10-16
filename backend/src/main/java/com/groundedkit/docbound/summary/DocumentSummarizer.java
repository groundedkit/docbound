package com.groundedkit.docbound.summary;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

/**
 * Writes a short summary of a whole document with the chat model. A document that doesn't fit one call is
 * summarized in sections first, and the section notes are then combined ("map-reduce"), so the summary covers the
 * whole document rather than its beginning.
 */
@Component
public class DocumentSummarizer {

	static final String SECTION_PROMPT = """
			You take notes on one part of a longer document, so that a summary of the whole document can be written \
			from the notes. List every topic in this part as a short bullet point starting with "- ", with its key \
			facts (numbers, names, deadlines, rules), copied as stated. If a rule applies only to some people (for \
			example one country, state or role), say who it applies to. Use only the text, and never combine facts \
			from different sections into a new claim. No introduction or closing remarks.
			""";

	static final String SUMMARY_PROMPT = """
			You write the summary of a document for someone deciding whether it answers their question. Start with \
			one or two sentences saying what the document is and what it covers. Then list its main topics as bullet \
			points starting with "- ", in the document's order, each with its most important facts. Cover every \
			topic in the text, not only the first or last ones; if the text is notes on several parts, include each \
			part. Use only the text: copy facts as they are stated, keep each fact under the topic it belongs to, say \
			who a rule applies to if it applies only to some people, and never combine facts from different \
			sections into a new claim. \
			Leave out any detail you're unsure of. At most 250 words. No closing remarks.
			""";

	/** Output limits, well above the longest summary in RetrievalEval (about 600 tokens). */
	static final int MAX_NOTES_TOKENS = 700;

	static final int MAX_SUMMARY_TOKENS = 1000;

	private final ChatClient chatClient;

	private final SummaryProperties properties;

	public DocumentSummarizer(ChatClient.Builder chatClientBuilder, SummaryProperties properties) {
		this.chatClient = chatClientBuilder.build();
		this.properties = properties;
	}

	/**
	 * @param title  the document's readable title, e.g. "Employee Handbook"
	 * @param chunks the document's chunks in order
	 */
	public String summarize(String title, List<String> chunks) {
		List<String> sections = sections(chunks, properties.sectionChars());
		String text;
		if (sections.size() == 1) {
			text = sections.getFirst();
		}
		else {
			StringBuilder notes = new StringBuilder();
			for (int i = 0; i < sections.size(); i++) {
				String part = "part %d of %d".formatted(i + 1, sections.size());
				String sectionNotes = ask(SECTION_PROMPT, MAX_NOTES_TOKENS,
						"Document: %s (%s)%n%n%s".formatted(title, part, sections.get(i)));
				notes.append("Notes on %s:%n%s%n%n".formatted(part, sectionNotes));
			}
			text = notes.toString().strip();
		}
		return withoutEchoedHeader(ask(SUMMARY_PROMPT, MAX_SUMMARY_TOKENS, "Document: %s%n%n%s".formatted(title, text)));
	}

	/** The model sometimes repeats the "Document: <title>" line it was given before the summary itself. */
	static String withoutEchoedHeader(String summary) {
		return summary.startsWith("Document:") ? summary.replaceFirst("^Document:[^\\n]*\\s*", "") : summary;
	}

	private String ask(String instructions, int maxTokens, String text) {
		// The cap stops a small model that falls into repeating itself from generating until the context is full.
		// Ollama's own num_predict: the portable maxTokens option doesn't reach Ollama through ChatClient.
		String reply = chatClient.prompt(new Prompt(List.of(new SystemMessage(instructions), new UserMessage(text))))
			.options(OllamaChatOptions.builder().numPredict(maxTokens))
			.call()
			.content();
		return reply == null ? "" : reply.strip();
	}

	/** Consecutive chunks joined into sections of at most {@code maxChars} (a single huge chunk stays whole). */
	static List<String> sections(List<String> chunks, int maxChars) {
		List<String> sections = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String chunk : chunks) {
			if (!current.isEmpty() && current.length() + chunk.length() + 2 > maxChars) {
				sections.add(current.toString());
				current.setLength(0);
			}
			if (!current.isEmpty()) {
				current.append("\n\n");
			}
			current.append(chunk.strip());
		}
		if (!current.isEmpty()) {
			sections.add(current.toString());
		}
		return sections;
	}

}
