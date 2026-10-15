# Docbound

**A production-ready RAG starter for Spring Boot.** Upload your documents and ask questions: answers stream in, cite the exact passages they came from, and say "not found" instead of guessing. Everything runs on your machine with [Ollama](https://ollama.com), so no API keys are needed and documents never leave it.

[![CI](https://github.com/groundedkit/docbound/actions/workflows/ci.yml/badge.svg)](https://github.com/groundedkit/docbound/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

- **Cited, grounded answers:** every claim links to its source passage; off-topic questions get "not found" instead of a made-up answer.
- **Measured retrieval:** structure-aware chunking, tuned with an included evaluation harness (results below).
- **A complete app:** Spring Boot 4.1 + Spring AI 2.0 backend, React UI, PostgreSQL + pgvector, tests, and one-command Docker setup.

## Stack

**Backend:** Java 21 · Spring Boot 4.1 · Spring AI 2.0 · PostgreSQL 17 + pgvector · Ollama (`llama3.2` chat, `nomic-embed-text` embeddings) · Flyway · Testcontainers

**Frontend:** React 19 · TypeScript · Vite · Vitest, with no UI library (plain CSS, light and dark themes)

## Quick start

**1. Install and start [Docker](https://www.docker.com/products/docker-desktop/).** On macOS and Windows, open Docker Desktop and wait until it shows "Engine running".

**2. Install and start [Ollama](https://ollama.com).** It runs on your machine rather than in Docker, because Docker on macOS can't use the GPU and native Ollama is many times faster.
- macOS/Windows: open the Ollama app; it runs in the menu bar or system tray. With Homebrew: `brew install ollama`, then `brew services start ollama`.
- Linux: see [Linux](#linux) below.

Check that it's running. This should print a table, not "could not connect":

```bash
ollama list
```

**3. Download the models (~2.3 GB, one time).** Doing this first shows a progress bar. Otherwise the backend downloads them silently on its first start.

```bash
ollama pull llama3.2
ollama pull nomic-embed-text
```

**4. Start the app.** The first build takes about a minute.

```bash
docker compose up --build
```

Open **http://localhost:3000**, upload a document and ask a question.

To run the backend from your IDE instead, start only Postgres and run `DocboundApplication`:

```bash
docker compose up postgres
```

No native Ollama (e.g. on a Linux server)? Run it as a container too. It's CPU-only, so chat answers are slower:

```bash
docker compose -f docker-compose.yml -f docker-compose.ollama.yml up --build
```

### Linux

Ollama listens only on `127.0.0.1` by default, so the backend container can't reach it. Let it accept connections from containers:

```bash
sudo systemctl edit ollama   # add the two lines below, save, then:
sudo systemctl restart ollama
```

```ini
[Service]
Environment="OLLAMA_HOST=0.0.0.0"
```

`0.0.0.0` means every network interface. On a machine reachable from other networks, firewall port 11434 or use the containerized Ollama above instead.

### Frontend development

With the backend running on port 8080, start Vite's dev server with hot reload. It proxies `/api` to the backend:

```bash
cd frontend && npm install && npm run dev
```

In production, the `frontend` container serves the built app with nginx and proxies `/api` to the backend. Response buffering is turned off for `/api`, so chat tokens stream through as soon as they're generated.

## UI

- **Documents** (left): upload with the button or by dragging files in. Each file shows its part count; failed uploads show the server's reason (e.g. unsupported type). Delete with ×.
- **Chat**: answers stream in as they're generated. Citations like **1** are buttons, and each finished answer lists the sources it actually used. A "not found" answer is shown muted, without citations. **Stop** cancels generation.
- **Source panel** (right, or a bottom sheet on phones): click a citation to see the exact passage, its file, part number and match score. `Esc` closes it.

The frontend code is small and has no dependencies beyond React:

| File | What it does |
|---|---|
| `src/api.ts` | Typed API client; `streamChat()` yields the chat events |
| `src/sse.ts` | Server-sent events parser for `fetch` streams (`EventSource` can't send POST) |
| `src/useChat.ts` | Conversation state and the request lifecycle (send, stop, errors) |
| `src/citations.ts` | Splits answer text into text and `[n]` citation parts |
| `src/components/` | `DocumentPanel`, `ChatPanel`, `AssistantMessage`, `SourcePanel` |

## API

```bash
# Upload a file (pdf, md, markdown, txt, docx, html; up to 20 MB)
curl -F "file=@handbook.pdf" http://localhost:8080/api/documents

# List documents
curl http://localhost:8080/api/documents

# Semantic search over the chunks (checks retrieval quality before chat is involved)
curl "http://localhost:8080/api/search?q=vacation+policy&topK=5"

# Delete a document and all its chunks
curl -X DELETE http://localhost:8080/api/documents/<id>

# Ask a question (streams server-sent events)
curl -N http://localhost:8080/api/chat -H "Content-Type: application/json" \
  -d '{"question": "How many vacation days do I get?"}'
```

### Chat stream

`POST /api/chat` takes `{"question": "...", "topK": 5}` (`topK` optional, 1–10) and streams these events. Each event's data is JSON:

| Event | Data | Notes |
|---|---|---|
| `sources` | `{"sources": [{"index", "documentId", "filename", "chunkIndex", "score", "text"}]}` | Sent first. The model cites these as `[index]`. |
| `token` | `{"text": "..."}` | Answer text, piece by piece. |
| `done` | `{"citedSources": [1, 3], "promptTokens", "completionTokens", "durationMs"}` | `citedSources` lists the sources the answer actually used; show only these as citations. |
| `error` | `{"message": "..."}` | Generation failed part-way. |

```
event:sources
data:{"sources":[{"index":1,"documentId":"…","filename":"handbook.pdf","chunkIndex":4,"score":0.71,"text":"…"}]}

event:token
data:{"text":"You get 27 days of paid vacation"}

event:token
data:{"text":" [1]."}

event:done
data:{"citedSources":[1],"promptTokens":753,"completionTokens":32,"durationMs":1890}
```

**Grounding rules.** The model is told to answer only from the sources, cite them, and reply exactly `I couldn't find that in your documents.` when they don't contain the answer. If no chunk scores above `app.chat.similarity-threshold` (0.45), that reply is returned immediately without calling the LLM.

Errors are returned as RFC 9457 problem JSON: `415` for unsupported file types, `422` for files with no extractable text (e.g. scanned PDFs), `413` for files that are too large, and `404` for unknown IDs.

## Troubleshooting

**The UI says "Can't reach the backend."** Check the backend's log:

```bash
docker compose logs backend
```

- **`Couldn't connect to Ollama at …`**: Ollama isn't running, or (on Linux) isn't reachable from containers. Start it (see Quick start, step 2). The backend retries on its own and the page recovers within a few seconds, so there's nothing to restart.
- **The log is still running, with no `Started DocboundApplication` line**: the backend is downloading models you haven't pulled yet. Wait, or stop it and run step 3 to see progress.
- **`Port 8080 was already in use`** (or 5432, 3000): another program uses that port. Stop it, or change the left-hand port in `docker-compose.yml`.

**Answers are slow.** The first question after a while takes a few extra seconds while Ollama loads the model into memory. If every answer is slow, check that you're using native Ollama, not the CPU-only container.

**"I couldn't find that in your documents" for something that is in them.** Check what search returns with `curl "localhost:8080/api/search?q=your+question"`. If the right passage isn't there, try rephrasing with words from the document. Documents uploaded before a chunking setting changed keep their old chunks until you upload them again.

## How ingestion works

1. **Parse:** Apache Tika extracts text from the uploaded file.
2. **Split:** `StructureAwareTextSplitter` cuts along the document's structure:
   - Each heading (Markdown `#` or a title-like line in PDF/DOCX text) starts a new chunk, so unrelated sections don't share an embedding.
   - Once a chunk holds 60 tokens, it ends at the next paragraph (`app.ingestion.paragraph-break-tokens`). Prose without headings therefore becomes roughly one chunk per paragraph, while short paragraphs are merged.
   - Whole sentences are packed up to 400 tokens (`app.ingestion.chunk-size`). A longer passage continues in a new chunk that repeats the heading and the last ~100 tokens (`app.ingestion.chunk-overlap`), so an answer on a boundary isn't lost.

   Changing these settings only affects new uploads. Re-upload existing documents to re-chunk them.
3. **Embed + store:** each chunk is embedded by Ollama and saved to the `vector_store` table, tagged with `document_id`, `filename` and `chunk_index` metadata.
4. **Track:** a row in `documents` records the upload. Steps 3 and 4 run in a single transaction, so a failed upload leaves nothing behind.

## Configuration

Everything lives in [`backend/src/main/resources/application.yml`](backend/src/main/resources/application.yml). To switch embedding models, change `spring.ai.ollama.embedding.model` **and** the vector dimension: both `spring.ai.vectorstore.pgvector.dimensions` and the `vector(768)` column (via a new Flyway migration).

## Tests

```bash
cd backend && ./mvnw test
cd frontend && npm test
```

Unit and web tests run anywhere. The pgvector integration test needs Docker and is skipped automatically without it. Tests use a deterministic fake embedding model, so Ollama isn't required.

## Retrieval eval

`RetrievalEval` measures how well search finds the right passage. It ingests the documents in `backend/src/test/resources/eval/docs` with different splitter settings: a handbook, an API reference, a runbook, and two prose documents without headings. It then asks the 55 questions in `questions.tsv`, 19 of them about the prose documents, and checks whether the returned chunks contain the expected answer. It uses real embeddings, so Ollama must be running:

```bash
cd backend && ./mvnw test -Dtest=RetrievalEval -Deval=true
```

Add your own documents and questions to tune chunking for your data. Results that chose the current defaults (`nomic-embed-text`; chat sends the top 5 chunks to the model):

| Splitter | Avg chunk (tokens) | Answer in top 1 | Answer in top 5 | Prose answers in top 5 | MRR |
|---|---|---|---|---|---|
| Spring AI `TokenTextSplitter`, 800 | 629 | 75% | 96% | 17/19 | 0.823 |
| Structure-aware 400/100, no paragraph breaks (previous default) | 148 | 71% | 95% | 16/19 | 0.816 |
| Structure-aware 400/100, paragraph break at 120 tokens | 131 | 75% | 100% | 19/19 | 0.849 |
| **Structure-aware 400/100, paragraph break at 60 tokens (default)** | 102 | **87%** | **100%** | **19/19** | **0.926** |
| Structure-aware 400/100, paragraph break at 40 tokens | 86 | 85% | 98% | 18/19 | 0.908 |

With paragraph breaks on, the chunk size (300, 400 or 600) made no difference on this corpus, because almost every paragraph is shorter than the limit. 60 tokens was the best paragraph threshold at every size.

`RetrievalEval#relevanceScores` compares top similarity scores for answerable questions and for the off-topic questions in `offtopic.txt`; it's how the chat threshold of 0.45 was chosen. 0.45 is the highest value that keeps all 55 answerable questions: the vaguest one, "What would they do differently next time?", scores 0.475. It rejects 3 of the 16 off-topic questions without calling the LLM. The rest score as high as real answers (e.g. "What is the company's dress code?" at 0.64) and are left to the model's "not found" rule, which declined all of them in testing.

`nomic-embed-text`'s `search_query:`/`search_document:` prefixes were also tested and didn't improve results, so they aren't used.

## License

[Apache License 2.0](LICENSE). Use it freely in personal and commercial projects.
