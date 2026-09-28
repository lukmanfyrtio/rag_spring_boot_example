package com.example.rag;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * ETL: watches the docs folder and keeps Chroma in sync with it.
 * <ul>
 *   <li>new file      -> read (Tika) -> split into chunks -> embed + store</li>
 *   <li>changed file  -> delete old chunks, store new ones</li>
 *   <li>deleted file  -> delete its chunks</li>
 * </ul>
 * Each chunk carries {@code file_name} and {@code file_hash} metadata, so after a
 * restart files that are already in Chroma are not embedded again.
 */
@Service
public class DocsIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocsIngestionService.class);

    static final String FILE_NAME = "file_name";
    static final String FILE_HASH = "file_hash";

    private final VectorStore vectorStore;
    private final Path docsDir;
    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(500)
            .build();

    /** file name -> content hash of what is currently in Chroma */
    private final Map<String, String> ingested = new ConcurrentHashMap<>();

    public DocsIngestionService(VectorStore vectorStore, @Value("${rag.docs.dir}") String docsDir) {
        this.vectorStore = vectorStore;
        this.docsDir = Path.of(docsDir).toAbsolutePath();
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${rag.docs.scan-interval-ms}")
    public synchronized void scan() {
        try {
            Files.createDirectories(docsDir);
            Map<String, Path> files = listFiles();

            for (var entry : files.entrySet()) {
                String name = entry.getKey();
                Path file = entry.getValue();
                if (isStillBeingWritten(file)) {
                    continue; // pick it up on the next scan
                }
                String hash = sha256(file);
                if (hash.equals(ingested.get(name))) {
                    continue;
                }
                if (!ingested.containsKey(name) && alreadyInStore(name, hash)) {
                    log.info("Already in vector store, skipping: {}", name);
                    ingested.put(name, hash);
                    continue;
                }
                ingest(name, file, hash);
            }

            for (String name : List.copyOf(ingested.keySet())) {
                if (!files.containsKey(name)) {
                    log.info("File removed, deleting its chunks: {}", name);
                    vectorStore.delete(byFileName(name));
                    ingested.remove(name);
                }
            }
        }
        catch (Exception e) {
            log.error("Docs scan failed: {}", e.getMessage(), e);
        }
    }

    private void ingest(String name, Path file, String hash) {
        log.info("Ingesting {} ...", name);
        long start = System.currentTimeMillis();

        // Extract
        List<Document> docs = new TikaDocumentReader(new FileSystemResource(file)).read();

        // Transform: add our metadata, drop empty docs, split into chunks
        List<Document> withMetadata = docs.stream()
                .filter(d -> d.getText() != null && !d.getText().isBlank())
                .map(d -> {
                    Map<String, Object> metadata = new HashMap<>(d.getMetadata());
                    metadata.put(FILE_NAME, name);
                    metadata.put(FILE_HASH, hash);
                    return new Document(d.getText(), metadata);
                })
                .toList();
        if (withMetadata.isEmpty()) {
            log.warn("No text found in {} (scanned PDF / image?), skipping", name);
            ingested.put(name, hash);
            return;
        }
        List<Document> chunks = splitter.apply(withMetadata);

        // Load (replace any older version of this file)
        vectorStore.delete(byFileName(name));
        vectorStore.add(chunks);
        ingested.put(name, hash);

        log.info("Ingested {}: {} chunks in {} ms", name, chunks.size(), System.currentTimeMillis() - start);
    }

    private boolean alreadyInStore(String name, String hash) {
        var b = new FilterExpressionBuilder();
        List<Document> found = vectorStore.similaritySearch(SearchRequest.builder()
                .query(name)
                .topK(1)
                .filterExpression(b.and(b.eq(FILE_NAME, name), b.eq(FILE_HASH, hash)).build())
                .build());
        return found != null && !found.isEmpty();
    }

    private static Filter.Expression byFileName(String name) {
        return new FilterExpressionBuilder().eq(FILE_NAME, name).build();
    }

    private Map<String, Path> listFiles() throws IOException {
        try (Stream<Path> stream = Files.list(docsDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isTempFile(p.getFileName().toString()))
                    .collect(Collectors.toMap(p -> p.getFileName().toString(), p -> p));
        }
    }

    private static boolean isTempFile(String name) {
        return name.startsWith(".") || name.startsWith("~$")
                || name.endsWith(".tmp") || name.endsWith(".crdownload") || name.endsWith(".part");
    }

    private static boolean isStillBeingWritten(Path file) throws IOException {
        return System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() < 2000;
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
