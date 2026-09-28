package com.example.rag;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

@RestController
public class RagController {

    static final int TOP_K = 4;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public RagController(ChatClient.Builder builder, VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.chatClient = builder
                .defaultSystem("""
                        You are a helpful assistant that answers questions about the user's documents.
                        Use only the provided context. If the answer is not in the context, say you don't know.

                        Format every answer in Markdown so it is easy to read:
                        - Start with a short direct answer (1-2 sentences).
                        - Then give details with headings, bullet points or numbered steps.
                        - Use a table when comparing items or listing fields/parameters.
                        - Put code, JSON, URLs and HTTP requests in fenced code blocks with a language tag.
                        - Use **bold** for key terms. Keep paragraphs short.
                        - End with a line "Sumber:" / "Sources:" listing the file names you used.
                        Answer in the same language as the question.
                        """)
                // RAG: search Chroma for chunks similar to the question and add them to the prompt
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(SearchRequest.builder().topK(TOP_K).build())
                        .build())
                .build();
    }

    /** Ask a question, answered with the retrieved chunks as context. */
    @GetMapping("/ask")
    public Answer ask(@RequestParam String q) {
        ChatClientResponse response = chatClient.prompt().user(q).call().chatClientResponse();

        @SuppressWarnings("unchecked")
        List<Document> docs = (List<Document>) response.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);

        String answer = response.chatResponse().getResult().getOutput().getText();
        return new Answer(answer, docs == null ? List.of() : docs.stream().map(Source::of).toList());
    }

    /** Same as /ask, but streams the Markdown answer as plain text while it is generated. */
    @GetMapping(value = "/ask/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    public Flux<String> askStream(@RequestParam String q) {
        return chatClient.prompt().user(q).stream().content();
    }

    /** Only the retrieval step: see which chunks match a query (no LLM call). */
    @GetMapping("/search")
    public List<Source> search(@RequestParam String q, @RequestParam(defaultValue = "" + TOP_K) int topK) {
        return vectorStore.similaritySearch(SearchRequest.builder().query(q).topK(topK).build())
                .stream().map(Source::of).toList();
    }

    public record Answer(String answer, List<Source> sources) {
    }

    public record Source(Object file, Double score, String text) {
        static Source of(Document d) {
            return new Source(d.getMetadata().get(DocsIngestionService.FILE_NAME), d.getScore(), d.getText());
        }
    }
}
