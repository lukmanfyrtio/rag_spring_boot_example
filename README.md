# Spring AI RAG demo (ChromaDB + Gemini)

A small app for trying RAG:

- Files in `docs/` are read (Apache Tika: PDF, DOCX, TXT, MD, HTML, ...), split into chunks, embedded and stored in **ChromaDB**.
- The folder is checked every 5 s. New or changed files are loaded again, and deleted files are removed from Chroma.
- Questions go to `/ask`. The `QuestionAnswerAdvisor` finds the top 4 chunks and gives them to the LLM as context.
- **Gemini** provides the chat model (`gemini-2.5-flash`) and the embeddings (`gemini-embedding-001`). The app reaches it through Gemini's OpenAI-compatible API with the Spring AI OpenAI starter.

```
docs/ ──(scan every 5s)──> Tika reader ──> TokenTextSplitter ──> Gemini embeddings ──> ChromaDB
                                                                                          │
question ──> QuestionAnswerAdvisor ──(similarity search)──────────────────────────────────┘
                     └──> question + chunks ──> gemini-2.5-flash ──> answer
```

## Run

**1. Get a Gemini API key** at https://aistudio.google.com/apikey

**2. Start Chroma (in WSL, from the project folder):**

```bash
cd /mnt/c/Users/W5NXL/cashUPProject/rag_spring_boot_example
docker compose up -d
```

**3. Start the app (Windows, needs Java 21 + Maven):**

```powershell
$env:GEMINI_API_KEY = "your-key"
mvn spring-boot:run
```

On start, the app loads everything already in `docs/`. Watch the log for `Ingested <file>: N chunks`.

**4. Try it:**

- UI: http://localhost:8080
- `GET http://localhost:8080/ask?q=What authentication does the API use?`
- `GET http://localhost:8080/search?q=authentication&topK=5` returns only the retrieval step (no LLM call). It is useful for checking what the retriever finds.

Copy another file into `docs/`. Within a few seconds the log shows that it was ingested, and you can ask about it.

## Profiles (switch AI provider)

Each provider has its own YAML file in `src/main/resources`:

| Profile | File | Chat / embedding | Needs |
|---|---|---|---|
| `gemini` (default) | `application-gemini.yml` | `gemini-2.5-flash` / `gemini-embedding-001` | `GEMINI_API_KEY` |
| `openai` | `application-openai.yml` | `gpt-4.1-mini` / `text-embedding-3-small` | `OPENAI_API_KEY` |
| `ollama` | `application-ollama.yml` | `llama3.2` / `nomic-embed-text` (local) | `docker compose --profile ollama up -d` |

```powershell
mvn spring-boot:run                                          # gemini
mvn spring-boot:run "-Dspring-boot.run.profiles=openai"
mvn spring-boot:run "-Dspring-boot.run.profiles=ollama"
# or: $env:SPRING_PROFILES_ACTIVE = "ollama"
```

Each profile uses its own Chroma collection (`rag-docs-gemini`, `rag-docs-openai`, `rag-docs-ollama`), because each embedding model produces vectors of a different size. When you switch profiles, the docs are embedded again into that profile's collection the first time.

To add another provider, create `application-<name>.yml` and set `spring.ai.model.chat` / `spring.ai.model.embedding` to pick which Spring AI starter is active. Add the starter to `pom.xml` if it is not there yet.

## Config (`src/main/resources/application.yml`)

| Property | Default | |
|---|---|---|
| `rag.docs.dir` | `docs` | watched folder |
| `rag.docs.scan-interval-ms` | `5000` | scan interval |
| `spring.ai.openai.chat.options.model` | `gemini-2.5-flash` | any Gemini chat model, e.g. `gemini-2.5-pro` |
| `spring.ai.openai.embedding.options.model` | `gemini-embedding-001` | embedding model. If you change it, use a new collection name |
| `spring.ai.vectorstore.chroma.collection-name` | `rag-docs-gemini` | Chroma collection |

The chunk size (500 tokens) is set in `DocsIngestionService` and `topK` (4) is set in `RagController`.

## Notes / troubleshooting

- **Never commit your API key.** It is read from the `GEMINI_API_KEY` environment variable.
- **429 / rate limit:** the free tier limits embedding requests per minute, so a big PDF can hit it during ingestion. The scan logs the error and retries the file on the next scan (every 5 s).
- **Model not found:** Google retires model names over time. Check the current list at https://ai.google.dev/gemini-api/docs/models and update `application.yml`.
- **Docker in WSL:** WSL2 normally forwards `localhost:8000` to Windows. If it does not, run `hostname -I` in WSL and put that IP in `spring.ai.vectorstore.chroma.client.host`.
- **Scanned PDFs** (images with no text layer) produce no text and are skipped with a warning.
- **Start from scratch:** run `docker compose down -v`. This deletes all Chroma data.
- Files deleted while the app is **stopped** stay in Chroma. To clean up, reset Chroma as described above.
