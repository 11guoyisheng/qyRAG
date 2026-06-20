package com.example.rag.service;

import com.example.rag.dto.RagDtos.ManualDocumentRequest;
import com.example.rag.dto.RagDtos.UploadResult;
import com.example.rag.service.RagMetadataStore.ChunkRecord;
import com.example.rag.service.RagMetadataStore.DocumentRecord;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RagDocumentService {
    private static final Logger log = LoggerFactory.getLogger(RagDocumentService.class);
    private static final int MAX_EMBEDDING_BATCH_SIZE = 10;
    private static final Pattern COSINE_CANDIDATE_PATTERN = Pattern.compile("[^。！？.!?]+[。！？.!?]?");

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;
    private final RagMetadataStore metadataStore;
    private final Path uploadDir;
    private final String chunkingStrategy;
    private final double cosineSimilarityThreshold;
    private final int cosineMinChunkChars;
    private final int cosineMaxChunkChars;
    private final int cosineMaxCandidateChars;

    public RagDocumentService(VectorStore vectorStore, EmbeddingModel embeddingModel, RagMetadataStore metadataStore,
                              @Value("${rag.upload-dir}") String uploadDir,
                              @Value("${rag.chunking.strategy:cosine}") String chunkingStrategy,
                              @Value("${rag.chunking.cosine.similarity-threshold:0.72}") double cosineSimilarityThreshold,
                              @Value("${rag.chunking.cosine.min-chunk-chars:300}") int cosineMinChunkChars,
                              @Value("${rag.chunking.cosine.max-chunk-chars:1800}") int cosineMaxChunkChars,
                              @Value("${rag.chunking.cosine.max-candidate-chars:500}") int cosineMaxCandidateChars) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.metadataStore = metadataStore;
        this.uploadDir = Path.of(uploadDir);
        this.chunkingStrategy = chunkingStrategy;
        this.cosineSimilarityThreshold = cosineSimilarityThreshold;
        this.cosineMinChunkChars = cosineMinChunkChars;
        this.cosineMaxChunkChars = cosineMaxChunkChars;
        this.cosineMaxCandidateChars = cosineMaxCandidateChars;
    }

    public Mono<UploadResult> upload(FilePart file, String departmentId, String moduleCode, String uploadedBy) {
        long started = System.nanoTime();
        log.info("RAG document upload start departmentId={} moduleCode={} fileName={} uploadedBy={}",
                departmentId, moduleCode, file.filename(), uploadedBy);
        return Mono.fromCallable(() -> prepareUpload(file, departmentId, moduleCode))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(upload -> log.info(
                        "RAG document upload prepared documentId={} fileName={} savedPath={} contentType={} elapsedMs={}",
                        upload.documentId(), upload.originalName(), upload.saved(), upload.contentType(),
                        elapsedMs(started)))
                .flatMap(upload -> file.transferTo(upload.saved())
                        .doOnSuccess(ignored -> log.info(
                                "RAG document file saved documentId={} savedPath={} elapsedMs={}",
                                upload.documentId(), upload.saved(), elapsedMs(started)))
                        .then(Mono.fromCallable(() -> indexDocument(upload, departmentId, moduleCode, uploadedBy))
                                .subscribeOn(Schedulers.boundedElastic())))
                .doOnSuccess(result -> log.info(
                        "RAG document upload finished documentId={} chunkCount={} elapsedMs={}",
                        result.documentId(), result.chunkCount(), elapsedMs(started)))
                .doOnError(ex -> log.error(
                        "RAG document upload failed departmentId={} moduleCode={} fileName={} elapsedMs={}",
                        departmentId, moduleCode, file.filename(), elapsedMs(started), ex));
    }

    public UploadResult uploadManual(ManualDocumentRequest request) {
        long started = System.nanoTime();
        String departmentId = requireScopeValue(request.departmentId(), "departmentId");
        String moduleCode = requireScopeValue(request.moduleCode(), "moduleCode");
        log.info("RAG manual document upload start departmentId={} moduleCode={} fileName={} chunkInputCount={}",
                departmentId, moduleCode, request.fileName(), request.chunks() == null ? 0 : request.chunks().size());
        checkScope(departmentId, moduleCode);

        String fileName = normalizeFileName(request.fileName());
        String uploadedBy = request.uploadedBy() == null || request.uploadedBy().isBlank()
                ? "system"
                : request.uploadedBy().trim();
        List<String> chunks = normalizeManualChunks(request.chunks());
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("手动分片内容不能为空");
        }

        metadataStore.ensureScope(departmentId, moduleCode);
        String documentId = UUID.randomUUID().toString().replace("-", "");
        String now = RagMetadataStore.now();
        List<Document> documentsToStore = new ArrayList<>();
        List<ChunkRecord> metadataChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunkId = documentId + "-" + i;
            String content = chunks.get(i);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("doc_id", documentId);
            metadata.put("chunk_id", chunkId);
            metadata.put("department_id", departmentId);
            metadata.put("module_code", moduleCode);
            metadata.put("file_name", fileName);
            metadata.put("chunk_index", i);
            metadata.put("uploaded_by", uploadedBy);
            metadata.put("status", "ACTIVE");
            metadata.put("created_at", now);
            metadata.put("source", fileName);
            metadata.put("manual_upload", true);
            documentsToStore.add(new Document(chunkId, content, metadata));
            metadataChunks.add(new ChunkRecord(chunkId, documentId, i, content,
                    fileName, departmentId, moduleCode, "ACTIVE", now, now));
        }

        log.info("RAG manual document vector store add start documentId={} chunkCount={}",
                documentId, documentsToStore.size());
        addDocumentsInBatches(documentsToStore);
        log.info("RAG manual document vector store add finished documentId={} chunkCount={}",
                documentId, documentsToStore.size());
        log.info("RAG manual document metadata save start documentId={}", documentId);
        metadataStore.saveDocument(new DocumentRecord(documentId, fileName, departmentId,
                moduleCode, uploadedBy, now, documentsToStore.size(), "manual://" + documentId), metadataChunks);
        log.info("RAG manual document upload finished documentId={} chunkCount={} elapsedMs={}",
                documentId, documentsToStore.size(), elapsedMs(started));
        return new UploadResult(documentId, fileName, documentsToStore.size());
    }

    public void deleteDocument(String documentId) {
        List<String> chunkIds = metadataStore.deleteDocument(documentId);
        if (!chunkIds.isEmpty()) {
            vectorStore.delete(chunkIds);
        }
    }

    public ChunkRecord updateChunk(String chunkId, String content, String status) {
        ChunkRecord updated = metadataStore.updateChunk(chunkId, content, status);
        vectorStore.delete(List.of(chunkId));
        if ("ACTIVE".equals(updated.status())) {
            vectorStore.add(List.of(toVectorDocument(updated)));
        }
        return updated;
    }

    public ChunkRecord addChunk(String documentId, String content) {
        ChunkRecord chunk = metadataStore.addChunk(documentId, content);
        vectorStore.add(List.of(toVectorDocument(chunk)));
        return chunk;
    }

    public void deleteChunk(String chunkId) {
        boolean deleted = metadataStore.deleteChunk(chunkId);
        if (deleted) {
            vectorStore.delete(List.of(chunkId));
        }
    }

    private PendingUpload prepareUpload(FilePart file, String departmentId, String moduleCode) throws IOException {
        checkScope(departmentId, moduleCode);
        Files.createDirectories(uploadDir);

        String documentId = UUID.randomUUID().toString().replace("-", "");
        String originalName = Optional.ofNullable(file.filename()).orElse("unknown.txt");
        Path saved = uploadDir.resolve(documentId + ext(originalName)).normalize();
        MediaType contentType = file.headers().getContentType();
        return new PendingUpload(
                documentId,
                originalName,
                saved,
                contentType == null ? null : contentType.toString()
        );
    }

    private UploadResult indexDocument(PendingUpload upload, String departmentId, String moduleCode, String uploadedBy) {
        metadataStore.ensureScope(departmentId, moduleCode);
        List<Document> rawDocuments = readDocuments(upload.saved(), upload.originalName(), upload.contentType());
        List<Document> chunks = splitDocuments(rawDocuments);
        List<Document> documentsToStore = new ArrayList<>();
        List<ChunkRecord> metadataChunks = new ArrayList<>();
        String now = RagMetadataStore.now();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            String chunkId = upload.documentId() + "-" + i;
            Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put("doc_id", upload.documentId());
            metadata.put("chunk_id", chunkId);
            metadata.put("department_id", departmentId);
            metadata.put("module_code", moduleCode);
            metadata.put("file_name", upload.originalName());
            metadata.put("chunk_index", i);
            metadata.put("uploaded_by", uploadedBy);
            metadata.put("status", "ACTIVE");
            metadata.put("created_at", now);
            documentsToStore.add(new Document(chunkId, chunk.getText(), metadata));
            metadataChunks.add(new ChunkRecord(chunkId, upload.documentId(), i, chunk.getText(),
                    upload.originalName(), departmentId, moduleCode, "ACTIVE", now, now));
        }

        if (!documentsToStore.isEmpty()) {
            addDocumentsInBatches(documentsToStore);
        }
        metadataStore.saveDocument(new DocumentRecord(upload.documentId(), upload.originalName(), departmentId,
                moduleCode, uploadedBy, now, documentsToStore.size(), upload.saved().toString()), metadataChunks);
        return new UploadResult(upload.documentId(), upload.originalName(), documentsToStore.size());
    }

    private List<Document> splitDocuments(List<Document> rawDocuments) {
        if ("cosine".equalsIgnoreCase(chunkingStrategy)) {
            return splitByCosineSimilarity(rawDocuments);
        }
        if (!"token".equalsIgnoreCase(chunkingStrategy)) {
            throw new IllegalArgumentException("Unsupported rag.chunking.strategy: " + chunkingStrategy);
        }
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(800)
                .withMinChunkSizeChars(300)
                .withMinChunkLengthToEmbed(20)
                .withMaxNumChunks(3000)
                .build();
        return splitter.apply(rawDocuments);
    }

    private List<Document> splitByCosineSimilarity(List<Document> rawDocuments) {
        List<Document> chunks = new ArrayList<>();
        for (Document rawDocument : rawDocuments) {
            List<String> candidates = splitIntoCosineCandidates(rawDocument.getText());
            if (candidates.isEmpty()) {
                continue;
            }

            List<float[]> embeddings = embedCandidates(candidates);
            StringBuilder currentChunk = new StringBuilder();
            Map<String, Object> metadata = new HashMap<>(rawDocument.getMetadata());

            for (int i = 0; i < candidates.size(); i++) {
                String candidate = candidates.get(i);
                if (currentChunk.isEmpty()) {
                    currentChunk.append(candidate);
                    continue;
                }

                double similarity = cosineSimilarity(embeddings.get(i - 1), embeddings.get(i));
                boolean semanticBreak = currentChunk.length() >= cosineMinChunkChars
                        && similarity < cosineSimilarityThreshold;
                boolean maxSizeBreak = currentChunk.length() + candidate.length() > cosineMaxChunkChars;
                if (semanticBreak || maxSizeBreak) {
                    addCosineChunk(chunks, currentChunk, metadata);
                    currentChunk.setLength(0);
                } else {
                    currentChunk.append(System.lineSeparator());
                }
                currentChunk.append(candidate);
            }
            addCosineChunk(chunks, currentChunk, metadata);
        }
        return chunks;
    }

    private List<String> splitIntoCosineCandidates(String text) {
        List<String> candidates = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return candidates;
        }
        String[] paragraphs = text.replace("\r\n", "\n").split("\\n+");
        for (String paragraph : paragraphs) {
            Matcher matcher = COSINE_CANDIDATE_PATTERN.matcher(paragraph);
            while (matcher.find()) {
                String candidate = matcher.group().trim();
                if (candidate.isEmpty()) {
                    continue;
                }
                splitLongCandidate(candidate, candidates);
            }
        }
        if (candidates.isEmpty()) {
            splitLongCandidate(text.trim(), candidates);
        }
        return candidates;
    }

    private void splitLongCandidate(String candidate, List<String> candidates) {
        if (candidate.length() <= cosineMaxCandidateChars) {
            candidates.add(candidate);
            return;
        }
        for (int start = 0; start < candidate.length(); start += cosineMaxCandidateChars) {
            int end = Math.min(start + cosineMaxCandidateChars, candidate.length());
            String part = candidate.substring(start, end).trim();
            if (!part.isEmpty()) {
                candidates.add(part);
            }
        }
    }

    private List<float[]> embedCandidates(List<String> candidates) {
        List<float[]> embeddings = new ArrayList<>();
        for (int start = 0; start < candidates.size(); start += MAX_EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + MAX_EMBEDDING_BATCH_SIZE, candidates.size());
            embeddings.addAll(embeddingModel.embed(candidates.subList(start, end)));
        }
        return embeddings;
    }

    private double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0 || left.length != right.length) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private void addCosineChunk(List<Document> chunks, StringBuilder content, Map<String, Object> metadata) {
        String text = content.toString().trim();
        if (text.length() <= 20) {
            return;
        }
        chunks.add(new Document(text, new HashMap<>(metadata)));
    }

    private void addDocumentsInBatches(List<Document> documents) {
        for (int start = 0; start < documents.size(); start += MAX_EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + MAX_EMBEDDING_BATCH_SIZE, documents.size());
            vectorStore.add(documents.subList(start, end));
        }
    }

    private Document toVectorDocument(ChunkRecord chunk) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("doc_id", chunk.documentId());
        metadata.put("chunk_id", chunk.chunkId());
        metadata.put("department_id", chunk.departmentId());
        metadata.put("module_code", chunk.moduleCode());
        metadata.put("file_name", chunk.fileName());
        metadata.put("chunk_index", chunk.chunkIndex());
        metadata.put("status", chunk.status());
        metadata.put("created_at", chunk.createdAt());
        metadata.put("updated_at", chunk.updatedAt());
        return new Document(chunk.chunkId(), chunk.content(), metadata);
    }

    private List<Document> readDocuments(Path path, String originalName, String contentType) {
        Resource resource = new FileSystemResource(path);
        String lowerName = originalName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".txt")) {
            TextReader reader = new TextReader(resource);
            reader.getCustomMetadata().put("source", originalName);
            return reader.read();
        }
        if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown")) {
            MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                    .withIncludeCodeBlock(true)
                    .withIncludeBlockquote(true)
                    .withAdditionalMetadata("source", originalName)
                    .build();
            return new MarkdownDocumentReader(resource, config).read();
        }
        return new TikaDocumentReader(resource).read().stream().map(doc -> {
            Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
            metadata.put("source", originalName);
            metadata.put("content_type", contentType);
            return new Document(doc.getText(), metadata);
        }).toList();
    }

    private String ext(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index < 0 ? "" : fileName.substring(index);
    }

    private String normalizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "manual-document.txt";
        }
        String normalized = fileName.trim();
        return normalized.contains(".") ? normalized : normalized + ".txt";
    }

    private List<String> normalizeManualChunks(List<String> chunks) {
        if (chunks == null) {
            return List.of();
        }
        return chunks.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .toList();
    }

    private String requireScopeValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value.trim();
    }

    private void checkScope(String departmentId, String moduleCode) {
        if (!departmentId.matches("[A-Za-z0-9_-]{1,64}") || !moduleCode.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("部门或模块编码非法");
        }
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private record PendingUpload(String documentId, String originalName, Path saved, String contentType) {
    }
}
