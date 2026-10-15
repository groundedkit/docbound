package com.groundedkit.docbound.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;

import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.util.Assert;

/**
 * Splits text into chunks that follow the document's structure instead of cutting at raw token counts.
 * <ul>
 * <li>Whole sentences are packed into chunks of up to {@code chunkSize} tokens. A sentence longer than the
 * limit on its own is the only thing ever cut mid-text.</li>
 * <li>A heading starts a new chunk, so unrelated sections aren't mixed into one embedding. Very short
 * sections (a heading plus a line or two) are merged with the next one rather than becoming tiny chunks.</li>
 * <li>When a section spans several chunks, each continuation chunk starts with the last sentences of the
 * previous chunk (up to {@code chunkOverlap} tokens), so an answer that falls on a boundary is still found
 * in one piece. Continuation chunks also repeat the section heading for context.</li>
 * <li>Paragraphs are soft boundaries: once a chunk holds at least {@code paragraphBreakTokens} tokens, it ends
 * at the next paragraph instead of running into it. Short paragraphs are still merged, but a chunk of
 * heading-less prose no longer spans several unrelated paragraphs.</li>
 * </ul>
 * Token counts use the cl100k encoding, the same as Spring AI's {@code TokenTextSplitter}. They're an
 * approximation of the embedding model's own tokenizer, which is fine for sizing.
 */
public class StructureAwareTextSplitter extends TextSplitter {

	private static final Encoding ENCODING = Encodings.newLazyEncodingRegistry()
		.getEncoding(EncodingType.CL100K_BASE);

	private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n[ \\t\\x0B\\f\\r]*\\n\\s*");

	/** Whitespace after sentence-ending punctuation, followed by something that can start a sentence. */
	private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?][\"')\\]]?)\\s+(?=[\\p{Lu}\\p{N}\"'(\\[`*#-])");

	private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,6}\\s+\\S.*");

	private final int chunkSize;

	private final int chunkOverlap;

	/**
	 * Sections smaller than this are merged into the following chunk instead of standing alone. Capped so
	 * that ordinary sections stay separate even with large chunk sizes.
	 */
	private static final int MAX_MIN_SECTION_TOKENS = 50;

	/** Chosen with {@code RetrievalEval}; see the README. */
	static final int DEFAULT_PARAGRAPH_BREAK_TOKENS = 60;

	private final int minSectionTokens;

	/** A chunk with at least this many content tokens ends at the next paragraph break. */
	private final int paragraphBreakTokens;

	public StructureAwareTextSplitter(int chunkSize, int chunkOverlap) {
		this(chunkSize, chunkOverlap, DEFAULT_PARAGRAPH_BREAK_TOKENS);
	}

	/**
	 * @param paragraphBreakTokens once a chunk holds this many tokens, it ends at the next paragraph break; a
	 * value of {@code chunkSize} or more effectively disables paragraph boundaries
	 */
	public StructureAwareTextSplitter(int chunkSize, int chunkOverlap, int paragraphBreakTokens) {
		Assert.isTrue(chunkSize > 0, "chunkSize must be positive");
		Assert.isTrue(chunkOverlap >= 0 && chunkOverlap < chunkSize, "chunkOverlap must be in [0, chunkSize)");
		Assert.isTrue(paragraphBreakTokens > 0, "paragraphBreakTokens must be positive");
		this.chunkSize = chunkSize;
		this.chunkOverlap = chunkOverlap;
		this.minSectionTokens = Math.min(chunkSize / 4, MAX_MIN_SECTION_TOKENS);
		this.paragraphBreakTokens = paragraphBreakTokens;
	}

	@Override
	protected List<String> splitText(String text) {
		List<Unit> units = toUnits(text);
		List<String> chunks = new ArrayList<>();
		List<Unit> current = new ArrayList<>();
		Unit heading = null;

		for (Unit unit : units) {
			if (unit.heading()) {
				if (contentTokens(current) >= minSectionTokens) {
					emit(chunks, current);
					current = new ArrayList<>();
				}
				current.add(unit);
				heading = unit;
				continue;
			}
			if (unit.paragraphStart() && contentTokens(current) >= paragraphBreakTokens) {
				// A new paragraph is a natural topic boundary, so no sentence overlap is carried over.
				emit(chunks, current);
				current = new ArrayList<>();
				if (heading != null) {
					current.add(heading);
				}
			}
			else if (tokens(current) + unit.tokens() > chunkSize && contentTokens(current) > 0) {
				emit(chunks, current);
				current = continuation(current, heading, unit.tokens());
			}
			current.add(unit);
		}
		if (contentTokens(current) > 0 || chunks.isEmpty()) {
			emit(chunks, current);
		}
		return chunks;
	}

	/**
	 * Starts the chunk that follows {@code previous} within the same section: the section heading, then as
	 * many trailing sentences of {@code previous} as fit in the overlap budget.
	 */
	private List<Unit> continuation(List<Unit> previous, Unit heading, int nextUnitTokens) {
		List<Unit> next = new ArrayList<>();
		int budget = chunkSize - nextUnitTokens;
		if (heading != null && heading.tokens() <= budget / 2) {
			next.add(heading);
			budget -= heading.tokens();
		}
		int overlapBudget = Math.min(chunkOverlap, budget);
		List<Unit> tail = new ArrayList<>();
		for (int i = previous.size() - 1; i >= 0; i--) {
			Unit unit = previous.get(i);
			if (unit.heading() || unit.tokens() > overlapBudget) {
				break;
			}
			tail.addFirst(unit);
			overlapBudget -= unit.tokens();
		}
		if (!tail.isEmpty()) {
			next.addAll(tail);
			next.set(next.size() - tail.size(), tail.getFirst().startingParagraph());
		}
		return next;
	}

	private static void emit(List<String> chunks, List<Unit> units) {
		StringBuilder text = new StringBuilder();
		for (Unit unit : units) {
			if (!text.isEmpty()) {
				text.append(unit.paragraphStart() ? "\n\n" : unit.separator());
			}
			text.append(unit.text());
		}
		if (!text.toString().isBlank()) {
			chunks.add(text.toString());
		}
	}

	private List<Unit> toUnits(String text) {
		List<Unit> units = new ArrayList<>();
		for (String paragraph : PARAGRAPH_BREAK.split(text.strip())) {
			String cleaned = paragraph.lines().map(String::stripTrailing).reduce((a, b) -> a + "\n" + b).orElse("");
			if (cleaned.isBlank()) {
				continue;
			}
			// PDF text often puts a heading on its own line directly above the body, without a blank line.
			String firstLine = cleaned.lines().findFirst().orElse("");
			if (isHeading(firstLine)) {
				units.add(Unit.heading(firstLine.strip()));
				cleaned = cleaned.substring(firstLine.length()).strip();
				if (cleaned.isEmpty()) {
					continue;
				}
			}
			addSentences(units, cleaned);
		}
		return units;
	}

	private void addSentences(List<Unit> units, String paragraph) {
		Matcher breaks = SENTENCE_BREAK.matcher(paragraph);
		int start = 0;
		String separator = null;
		while (true) {
			boolean found = breaks.find();
			String sentence = paragraph.substring(start, found ? breaks.start() : paragraph.length());
			addSentence(units, sentence, separator);
			if (!found) {
				return;
			}
			separator = breaks.group().contains("\n") ? "\n" : " ";
			start = breaks.end();
		}
	}

	/** {@code separator == null} marks the first sentence of a paragraph. */
	private void addSentence(List<Unit> units, String sentence, String separator) {
		IntArrayList tokens = ENCODING.encode(sentence);
		if (tokens.size() <= chunkSize) {
			units.add(Unit.sentence(sentence, tokens.size(), separator));
			return;
		}
		// A single "sentence" longer than a whole chunk (e.g. a table or a run-on list): cut it by tokens.
		for (int from = 0; from < tokens.size(); from += chunkSize) {
			IntArrayList window = new IntArrayList();
			for (int i = from; i < Math.min(from + chunkSize, tokens.size()); i++) {
				window.add(tokens.get(i));
			}
			units.add(Unit.sentence(ENCODING.decode(window).strip(), window.size(), from == 0 ? separator : " "));
		}
	}

	private static boolean isHeading(String line) {
		String trimmed = line.strip();
		if (MARKDOWN_HEADING.matcher(trimmed).matches()) {
			return true;
		}
		// Title-like line from PDF/DOCX/plain text: short, has letters, and doesn't end like a sentence.
		return !trimmed.isEmpty() && trimmed.length() <= 80 && trimmed.split("\\s+").length <= 10
				&& trimmed.chars().anyMatch(Character::isLetter) && !trimmed.matches(".*[.,;:!?)\\]]$")
				&& !trimmed.startsWith("-") && !trimmed.startsWith("*") && !trimmed.startsWith("|");
	}

	private static int tokens(List<Unit> units) {
		return units.stream().mapToInt(Unit::tokens).sum();
	}

	private static int contentTokens(List<Unit> units) {
		return units.stream().filter(unit -> !unit.heading()).mapToInt(Unit::tokens).sum();
	}

	/**
	 * A heading or a sentence. {@code separator} is the whitespace that joined it to the previous sentence
	 * in the same paragraph; {@code paragraphStart} means it begins a new paragraph.
	 */
	private record Unit(String text, int tokens, boolean heading, boolean paragraphStart, String separator) {

		static Unit heading(String text) {
			return new Unit(text, ENCODING.countTokens(text), true, true, null);
		}

		static Unit sentence(String text, int tokens, String separator) {
			return new Unit(text, tokens, false, separator == null, separator);
		}

		Unit startingParagraph() {
			return new Unit(text, tokens, heading, true, separator);
		}

	}

}
