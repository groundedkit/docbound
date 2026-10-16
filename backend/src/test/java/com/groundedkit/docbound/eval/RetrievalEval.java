package com.groundedkit.docbound.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.groundedkit.docbound.chat.DocumentTitles;
import com.groundedkit.docbound.ingestion.StructureAwareTextSplitter;
import com.groundedkit.docbound.summary.DocumentSummarizer;
import com.groundedkit.docbound.summary.SummaryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Retrieval quality benchmark: ingests the documents in {@code src/test/resources/eval/docs} with different
 * splitter settings and checks whether each question in {@code questions.tsv} retrieves a chunk containing
 * its expected answer. Uses real embeddings, so it needs a running Ollama and is skipped by a plain
 * {@code ./mvnw test}. Run it with:
 *
 * <pre>
 * ./mvnw test -Dtest=RetrievalEval -Deval=true [-Dollama.url=http://localhost:11434] [-Dollama.model=nomic-embed-text]
 * </pre>
 *
 * Reported metrics: <b>hit@1</b> (answer in the top chunk), <b>hit@3</b>, <b>hit@5</b> (answer in any of the
 * top five, which is what the chat step gives the model), <b>prose@5</b> (hit@5 for questions about
 * heading-less prose) and <b>MRR</b> (mean reciprocal rank of the first chunk containing
 * the answer). Average chunk size is shown because bigger chunks trivially "contain" more answers but give
 * the LLM more irrelevant text.
 */
@EnabledIfSystemProperty(named = "eval", matches = "true")
class RetrievalEval {

	/** Same as the chat endpoint's default {@code app.chat.top-k}. */
	private static final int TOP_K = 5;

	/** Eval documents written as continuous prose without headings (see issue #4). */
	private static final Set<String> PROSE_DOCUMENTS = Set.of("migration-retrospective.txt",
			"support-quarter-review.txt");

	@Test
	void compareSplitters() throws IOException {
		EmbeddingModel embeddings = embeddingModel();
		List<Document> documents = loadDocuments();
		List<Question> questions = loadQuestions();
		Set<String> proseAnswers = answersFoundIn(documents, questions, PROSE_DOCUMENTS);

		Map<String, Supplier<TextSplitter>> configs = new java.util.LinkedHashMap<>();
		configs.put("token-only 800 (Spring AI default)", () -> TokenTextSplitter.builder().withChunkSize(800).build());
		for (int size : new int[] { 300, 400, 600 }) {
			for (int paragraph : new int[] { Integer.MAX_VALUE, 120, 80, 60, 40 }) {
				configs.put("structured %d/%d paragraphs %s".formatted(size, size / 4,
						paragraph == Integer.MAX_VALUE ? "off" : "@" + paragraph + " tok"),
						() -> new StructureAwareTextSplitter(size, size / 4, paragraph));
			}
		}

		long proseQuestions = questions.stream().filter(q -> proseAnswers.contains(q.answer())).count();
		System.out.printf("%n%d documents, %d questions (%d about heading-less prose), top-%d%n", documents.size(),
				questions.size(), proseQuestions, TOP_K);
		System.out.printf("%-38s %6s %7s %6s %6s %6s %6s %10s%n", "splitter", "chunks", "avg tok", "hit@1", "hit@3",
				"hit@5", "MRR", "prose@5");
		for (var config : configs.entrySet()) {
			List<Document> chunks = config.getValue().get().apply(documents);
			SimpleVectorStore store = SimpleVectorStore.builder(embeddings).build();
			store.add(chunks);

			int hit1 = 0;
			int hit3 = 0;
			int hit5 = 0;
			int proseHit5 = 0;
			double reciprocalRanks = 0;
			List<String> misses = new ArrayList<>();
			for (Question question : questions) {
				List<Document> results = store
					.similaritySearch(SearchRequest.builder().query(question.text()).topK(TOP_K).build());
				int rank = rankOfAnswer(results, question.answer());
				hit1 += rank == 1 ? 1 : 0;
				hit3 += rank >= 1 && rank <= 3 ? 1 : 0;
				if (rank > 0) {
					hit5++;
					proseHit5 += proseAnswers.contains(question.answer()) ? 1 : 0;
					reciprocalRanks += 1.0 / rank;
				}
				else {
					misses.add(question.text());
				}
			}
			double avgTokens = chunks.stream().mapToInt(chunk -> chunk.getText().length()).average().orElse(0) / 4.3;
			System.out.printf("%-38s %6d %7.0f %5.0f%% %5.0f%% %5.0f%% %6.3f %6d/%d%n", config.getKey(), chunks.size(),
					avgTokens, percent(hit1, questions), percent(hit3, questions), percent(hit5, questions),
					reciprocalRanks / questions.size(), proseHit5, proseQuestions);
			if (Boolean.getBoolean("eval.verbose")) {
				misses.forEach(miss -> System.out.println("      miss: " + miss));
			}
		}
	}

	/**
	 * Issue #5: for questions about a whole document, how many of its main topics reach the model (the top five
	 * passages), with and without the document summaries written at upload. Also re-checks the specific questions
	 * with the summaries added, since a summary passage takes one of the five places. Needs Ollama's chat model too.
	 */
	@Test
	void wholeDocumentQuestions() throws IOException {
		EmbeddingModel embeddings = embeddingModel();
		List<Document> chunks = new StructureAwareTextSplitter(400, 100, 60).apply(loadDocuments());
		DocumentSummarizer summarizer = new DocumentSummarizer(ChatClient.builder(OllamaChatModel.builder()
			.ollamaApi(OllamaApi.builder()
				.baseUrl(System.getProperty("ollama.url", "http://localhost:11434"))
				// Summarizing a long section can take longer than the HTTP client's default 10 s read timeout.
				.restClientBuilder(RestClient.builder().requestFactory(slowModelRequests()))
				.build())
			.options(OllamaChatOptions.builder()
				.model(System.getProperty("ollama.chat-model", "llama3.2"))
				.temperature(0.2)
				.numCtx(8192)
				.build())
			.build()), new SummaryProperties(true, 12000));

		Map<String, List<String>> chunksByFile = new java.util.LinkedHashMap<>();
		for (Document chunk : chunks) {
			chunksByFile.computeIfAbsent(String.valueOf(chunk.getMetadata().get(TikaDocumentReader.METADATA_SOURCE)),
					file -> new ArrayList<>()).add(chunk.getText());
		}
		List<Document> summaries = new ArrayList<>();
		for (var file : chunksByFile.entrySet()) {
			String title = DocumentTitles.fromFilename(file.getKey());
			long start = System.nanoTime();
			String summary = summarizer.summarize(title, file.getValue());
			System.out.printf("%nsummary of %s (%d chunks, %.0f s):%n%s%n", file.getKey(), file.getValue().size(),
					(System.nanoTime() - start) / 1e9, summary);
			summaries.add(Document.builder()
				.text("Summary of the %s document:%n%n%s".formatted(title, summary))
				.metadata(Map.of(TikaDocumentReader.METADATA_SOURCE, file.getKey(), "kind", "summary"))
				.build());
		}

		SimpleVectorStore chunksOnly = SimpleVectorStore.builder(embeddings).build();
		chunksOnly.add(chunks);
		SimpleVectorStore withSummaries = SimpleVectorStore.builder(embeddings).build();
		withSummaries.add(chunks);
		withSummaries.add(summaries);

		String tsv = new ClassPathResource("eval/overview.tsv").getContentAsString(StandardCharsets.UTF_8);
		List<String[]> overview = tsv.lines().filter(l -> !l.isBlank() && !l.startsWith("#")).map(l -> l.split("\t")).toList();
		System.out.printf("%n%-60s %16s %16s %8s%n", "whole-document question", "chunks only", "with summaries", "summary");
		int topicsTotal = 0;
		int coveredBefore = 0;
		int coveredAfter = 0;
		int summaryRetrieved = 0;
		for (String[] row : overview) {
			String[] topics = row[2].split("\\|");
			int before = topicsCovered(chunksOnly, row[1], topics);
			int after = topicsCovered(withSummaries, row[1], topics);
			boolean found = withSummaries.similaritySearch(SearchRequest.builder().query(row[1]).topK(TOP_K).build())
				.stream()
				.anyMatch(d -> "summary".equals(d.getMetadata().get("kind")) && row[0].equals(d.getMetadata().get(TikaDocumentReader.METADATA_SOURCE)));
			topicsTotal += topics.length;
			coveredBefore += before;
			coveredAfter += after;
			summaryRetrieved += found ? 1 : 0;
			System.out.printf("%-60s %13d/%d %13d/%d %8s%n", row[1], before, topics.length, after, topics.length,
					found ? "top 5" : "missed");
		}
		System.out.printf("%-60s %15.0f%% %15.0f%% %6d/%d%n", "topic coverage", 100.0 * coveredBefore / topicsTotal,
				100.0 * coveredAfter / topicsTotal, summaryRetrieved, overview.size());

		List<Question> questions = loadQuestions();
		int hit5Before = 0;
		int hit5After = 0;
		for (Question question : questions) {
			hit5Before += rankOfAnswer(chunksOnly.similaritySearch(SearchRequest.builder().query(question.text()).topK(TOP_K).build()), question.answer()) > 0 ? 1 : 0;
			hit5After += rankOfAnswer(withSummaries.similaritySearch(SearchRequest.builder().query(question.text()).topK(TOP_K).build()), question.answer()) > 0 ? 1 : 0;
		}
		System.out.printf("specific questions, answer in top 5: chunks only %d/%d, with summaries %d/%d%n", hit5Before,
				questions.size(), hit5After, questions.size());
	}

	/** How many topics have at least one alternative in the top passages for the question (case-insensitive). */
	private static int topicsCovered(SimpleVectorStore store, String question, String[] topics) {
		String context = store.similaritySearch(SearchRequest.builder().query(question).topK(TOP_K).build())
			.stream()
			.map(Document::getText)
			.collect(java.util.stream.Collectors.joining("\n"))
			.toLowerCase(java.util.Locale.ROOT);
		int covered = 0;
		for (String topic : topics) {
			for (String alternative : topic.split("/")) {
				if (context.contains(alternative.strip().toLowerCase(java.util.Locale.ROOT))) {
					covered++;
					break;
				}
			}
		}
		return covered;
	}

	private static double percent(int hits, List<Question> questions) {
		return 100.0 * hits / questions.size();
	}

	/** Answers of the questions whose answer appears in one of the named documents. */
	private static Set<String> answersFoundIn(List<Document> documents, List<Question> questions, Set<String> names) {
		Set<String> answers = new HashSet<>();
		for (Document document : documents) {
			if (names.contains(String.valueOf(document.getMetadata().get(TikaDocumentReader.METADATA_SOURCE)))) {
				String text = normalize(document.getText());
				questions.stream().map(Question::answer).filter(text::contains).forEach(answers::add);
			}
		}
		return answers;
	}


	/**
	 * Prints the top similarity score for answerable vs. off-topic questions, to choose
	 * {@code app.chat.similarity-threshold}: below it, chat answers "not found" without calling the LLM.
	 */
	@Test
	void relevanceScores() throws IOException {
		SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel()).build();
		store.add(new StructureAwareTextSplitter(400, 100).apply(loadDocuments()));

		List<Double> answerable = new ArrayList<>();
		for (Question question : loadQuestions()) {
			double score = topScore(store, question.text());
			answerable.add(score);
			if (Boolean.getBoolean("eval.verbose")) {
				System.out.printf("   answerable %.3f  %s%n", score, question.text());
			}
		}
		String offTopicFile = new ClassPathResource("eval/offtopic.txt").getContentAsString(StandardCharsets.UTF_8);
		List<Double> offTopic = new ArrayList<>();
		for (String question : offTopicFile.lines().filter(l -> !l.isBlank() && !l.startsWith("#")).toList()) {
			double score = topScore(store, question);
			offTopic.add(score);
			System.out.printf("   off-topic %.3f  %s%n", score, question);
		}
		answerable.sort(null);
		offTopic.sort(null);
		System.out.printf("%nanswerable top score: min %.3f, p10 %.3f, median %.3f%n", answerable.getFirst(),
				answerable.get(answerable.size() / 10), answerable.get(answerable.size() / 2));
		System.out.printf("off-topic  top score: median %.3f, max %.3f%n", offTopic.get(offTopic.size() / 2),
				offTopic.getLast());
		for (double threshold = 0.40; threshold <= 0.601; threshold += 0.025) {
			final double t = threshold;
			System.out.printf("threshold %.2f: answerable kept %d/%d, off-topic rejected %d/%d%n", t,
					answerable.stream().filter(s -> s >= t).count(), answerable.size(),
					offTopic.stream().filter(s -> s < t).count(), offTopic.size());
		}
	}

	private static double topScore(SimpleVectorStore store, String question) {
		return store.similaritySearch(SearchRequest.builder().query(question).topK(1).build()).getFirst().getScore();
	}

	private static EmbeddingModel embeddingModel() {
		return new CachingEmbeddingModel(OllamaEmbeddingModel.builder()
			.ollamaApi(OllamaApi.builder().baseUrl(System.getProperty("ollama.url", "http://localhost:11434")).build())
			.options(OllamaEmbeddingOptions.builder()
				.model(System.getProperty("ollama.model", "nomic-embed-text"))
				.build())
			.build());
	}

	private static int rankOfAnswer(List<Document> results, String answer) {
		for (int i = 0; i < results.size(); i++) {
			if (normalize(results.get(i).getText()).contains(answer)) {
				return i + 1;
			}
		}
		return 0;
	}

	private static String normalize(String text) {
		return String.join(" ", text.split("\\s+")).strip();
	}

	private static List<Document> loadDocuments() throws IOException {
		List<Document> documents = new ArrayList<>();
		Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:eval/docs/*");
		for (Resource file : files) {
			documents.addAll(new TikaDocumentReader(file).get());
		}
		return documents;
	}

	private static List<Question> loadQuestions() throws IOException {
		String tsv = new ClassPathResource("eval/questions.tsv").getContentAsString(StandardCharsets.UTF_8);
		return tsv.lines()
			.filter(line -> !line.isBlank() && !line.startsWith("#"))
			.map(line -> line.split("\t"))
			.map(parts -> new Question(parts[0], normalize(parts[1])))
			.toList();
	}

	private record Question(String text, String answer) {
	}

	/** Most chunk texts repeat across configurations, so embed each distinct text only once. */
	private static final class CachingEmbeddingModel implements EmbeddingModel {

		private final EmbeddingModel delegate;

		private final Map<String, float[]> cache = new ConcurrentHashMap<>();

		CachingEmbeddingModel(EmbeddingModel delegate) {
			this.delegate = delegate;
		}

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			List<String> texts = request.getInstructions();
			List<String> missing = texts.stream().filter(text -> !cache.containsKey(text)).distinct().toList();
			if (!missing.isEmpty()) {
				List<float[]> vectors = delegate.embed(missing);
				for (int i = 0; i < missing.size(); i++) {
					cache.put(missing.get(i), vectors.get(i));
				}
			}
			List<Embedding> results = new ArrayList<>();
			for (int i = 0; i < texts.size(); i++) {
				results.add(new Embedding(cache.get(texts.get(i)), i));
			}
			return new EmbeddingResponse(results);
		}

		@Override
		public float[] embed(Document document) {
			return embed(document.getText());
		}

		@Override
		public float[] embed(String text) {
			return call(new EmbeddingRequest(List.of(text), null)).getResult().getOutput();
		}

	}

	private static JdkClientHttpRequestFactory slowModelRequests() {
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
		factory.setReadTimeout(java.time.Duration.ofMinutes(5));
		return factory;
	}

}
