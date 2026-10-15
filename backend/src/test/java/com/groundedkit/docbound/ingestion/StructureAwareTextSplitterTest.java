package com.groundedkit.docbound.ingestion;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructureAwareTextSplitterTest {

	private static final Encoding ENCODING = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

	@Test
	void keepsChunksWithinTheTokenLimitAndNeverCutsSentences() {
		String text = section("A", 60);

		List<String> chunks = split(text, 100, 0);

		assertThat(chunks).hasSizeGreaterThan(5);
		assertThat(chunks).allSatisfy(chunk -> assertThat(ENCODING.countTokens(chunk)).isLessThanOrEqualTo(105));
		String joined = String.join(" ", chunks);
		IntStream.range(0, 60).forEach(i -> assertThat(joined).contains(sentence("A", i)));
	}

	@Test
	void overlapRepeatsTheLastSentencesOfThePreviousChunk() {
		List<String> chunks = split(section("A", 60), 100, 30);

		for (int i = 1; i < chunks.size(); i++) {
			String previous = chunks.get(i - 1);
			String lastSentence = previous.substring(previous.lastIndexOf("Sentence "));
			assertThat(chunks.get(i)).as("chunk %d starts with the end of chunk %d", i, i - 1).contains(lastSentence);
		}
	}

	@Test
	void withoutOverlapNoSentenceAppearsTwice() {
		List<String> chunks = split(section("A", 60), 100, 0);

		Map<String, Long> counts = chunks.stream()
			.flatMap(chunk -> List.of(chunk.split("(?<=\\.)\\s+")).stream())
			.filter(s -> s.startsWith("Sentence"))
			.collect(Collectors.groupingBy(s -> s, Collectors.counting()));
		assertThat(counts.values()).containsOnly(1L);
	}

	@Test
	void headingStartsANewChunk() {
		String text = "## Vacation\n\n" + section("V", 8) + "\n\n## Expenses\n\n" + section("E", 8);

		List<String> chunks = split(text, 400, 50);

		assertThat(chunks).hasSize(2);
		assertThat(chunks.get(0)).startsWith("## Vacation").doesNotContain("Expenses");
		assertThat(chunks.get(1)).startsWith("## Expenses");
	}

	@Test
	void tinySectionsAreMergedInsteadOfBecomingTinyChunks() {
		String text = "# Title\n\nShort intro.\n\n## Part one\n\nOne line.\n\n## Part two\n\n" + section("B", 5);

		List<String> chunks = split(text, 400, 0);

		assertThat(chunks).hasSize(1);
		assertThat(chunks.getFirst()).startsWith("# Title").contains("## Part one").contains("## Part two");
	}

	@Test
	void detectsPdfStyleHeadingDirectlyAboveTheBody() {
		String text = "4. On-Call Rotation\n" + section("O", 8) + "\n\n5. Security Practices\n" + section("S", 8);

		List<String> chunks = split(text, 400, 0);

		assertThat(chunks).hasSize(2);
		assertThat(chunks.get(1)).startsWith("5. Security Practices");
	}

	@Test
	void continuationChunksRepeatTheSectionHeading() {
		List<String> chunks = split("## Remote work\n\n" + section("R", 40), 100, 20);

		assertThat(chunks).hasSizeGreaterThan(2).allSatisfy(chunk -> assertThat(chunk).startsWith("## Remote work"));
	}

	@Test
	void cutsASentenceOnlyWhenItIsLongerThanAChunk() {
		String runOn = IntStream.range(0, 300).mapToObj(i -> "word" + i).collect(Collectors.joining(" "));

		List<String> chunks = split("Intro sentence. " + runOn + ".", 100, 0);

		assertThat(chunks).hasSizeGreaterThan(3)
			.allSatisfy(chunk -> assertThat(ENCODING.countTokens(chunk)).isLessThanOrEqualTo(105));
		assertThat(String.join(" ", chunks)).contains("word0").contains("word299");
	}

	@Test
	void proseParagraphsBecomeSeparateChunks() {
		String prose = section("A", 6) + "\n\n" + section("B", 6) + "\n\n" + section("C", 6);

		List<String> chunks = new StructureAwareTextSplitter(400, 100, 60).apply(List.of(new Document(prose)))
			.stream()
			.map(Document::getText)
			.toList();

		assertThat(chunks).hasSize(3);
		assertThat(chunks.get(0)).contains("section A").doesNotContain("section B");
		assertThat(chunks.get(1)).contains("section B").doesNotContain("section A").doesNotContain("section C");
		assertThat(chunks.get(2)).contains("section C").doesNotContain("section B");
	}

	@Test
	void shortParagraphsAreStillMerged() {
		String prose = "First short paragraph.\n\nSecond short one.\n\nThird short one.";

		assertThat(new StructureAwareTextSplitter(400, 100, 60).apply(List.of(new Document(prose)))).hasSize(1);
	}

	@Test
	void paragraphBoundariesCanBeDisabled() {
		String prose = section("A", 6) + "\n\n" + section("B", 6);

		List<Document> chunks = new StructureAwareTextSplitter(400, 100, 400).apply(List.of(new Document(prose)));

		assertThat(chunks).hasSize(1);
	}

	@Test
	void paragraphChunksOfASectionRepeatItsHeading() {
		String text = "## Rollout\n\n" + section("A", 6) + "\n\n" + section("B", 6);

		List<String> chunks = new StructureAwareTextSplitter(400, 100, 60).apply(List.of(new Document(text)))
			.stream()
			.map(Document::getText)
			.toList();

		assertThat(chunks).hasSize(2).allSatisfy(chunk -> assertThat(chunk).startsWith("## Rollout"));
	}

	@Test
	void keepsSourceMetadataOnEveryChunk() {
		var document = new Document(section("A", 40), Map.of("filename", "guide.md"));

		List<Document> chunks = new StructureAwareTextSplitter(100, 20).apply(List.of(document));

		assertThat(chunks).hasSizeGreaterThan(1)
			.allSatisfy(chunk -> assertThat(chunk.getMetadata()).containsEntry("filename", "guide.md"));
	}

	@Test
	void shortTextIsASingleChunk() {
		assertThat(split("Just one sentence.", 400, 50)).containsExactly("Just one sentence.");
	}

	@Test
	void rejectsOverlapThatIsNotSmallerThanTheChunk() {
		assertThatThrownBy(() -> new StructureAwareTextSplitter(100, 100)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StructureAwareTextSplitter(0, 0)).isInstanceOf(IllegalArgumentException.class);
	}

	private static List<String> split(String text, int chunkSize, int overlap) {
		return new StructureAwareTextSplitter(chunkSize, overlap).apply(List.of(new Document(text)))
			.stream()
			.map(Document::getText)
			.toList();
	}

	private static String section(String name, int sentences) {
		return IntStream.range(0, sentences).mapToObj(i -> sentence(name, i)).collect(Collectors.joining(" "));
	}

	private static String sentence(String section, int i) {
		return "Sentence %d of section %s carries a few extra words for realism.".formatted(i, section);
	}

}
