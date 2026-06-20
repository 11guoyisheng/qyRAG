package com.example.rag.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
public class RagContextPostProcessorService {
    private static final Logger log = LoggerFactory.getLogger(RagContextPostProcessorService.class);
    private static final int MAX_EMBEDDING_BATCH_SIZE = 10;

    private final EmbeddingModel embeddingModel;
    private final boolean enabled;
    private final double relevanceSimilarityThreshold;
    private final double duplicateSimilarityThreshold;
    private final int maxChunks;

    public RagContextPostProcessorService(
            EmbeddingModel embeddingModel,
            @Value("${rag.context.post-processing.enabled:true}") boolean enabled,
            @Value("${rag.context.post-processing.relevance.similarity-threshold:0.55}") double relevanceSimilarityThreshold,
            @Value("${rag.context.post-processing.deduplication.similarity-threshold:0.92}") double duplicateSimilarityThreshold,
            @Value("${rag.context.max-chunks:6}") int maxChunks) {
        this.embeddingModel = embeddingModel;
        this.enabled = enabled;
        this.relevanceSimilarityThreshold = relevanceSimilarityThreshold;
        this.duplicateSimilarityThreshold = duplicateSimilarityThreshold;
        this.maxChunks = Math.max(1, maxChunks);
    }

    public List<Document> refine(List<Document> documents) {
        long started = System.nanoTime();
        int inputCount = documents == null ? 0 : documents.size();
        log.info("RAG context post-processing start enabled={} inputCount={} maxChunks={} relevanceThreshold={} duplicateThreshold={}",
                enabled, inputCount, maxChunks, relevanceSimilarityThreshold, duplicateSimilarityThreshold);
        if (documents == null || documents.isEmpty()) {
            log.info("RAG context post-processing skipped reason=no_documents elapsedMs={}", elapsedMs(started));
            return List.of();
        }
        if (!enabled) {
            List<Document> limitedDocuments = documents.stream().limit(maxChunks).toList();
            log.info("RAG context post-processing skipped reason=disabled outputCount={} elapsedMs={}",
                    limitedDocuments.size(), elapsedMs(started));
            return limitedDocuments;
        }

        List<Document> relevantDocuments = documents.stream()
                .filter(this::isRelevant)
                .filter(document -> StringUtils.hasText(document.getText()))
                .toList();
        log.info("RAG context relevance filtering finished inputCount={} relevantCount={}",
                documents.size(), relevantDocuments.size());
        if (relevantDocuments.isEmpty()) {
            log.info("RAG context post-processing finished outputCount=0 elapsedMs={}", elapsedMs(started));
            return List.of();
        }

        long embeddingStarted = System.nanoTime();
        log.info("RAG context embedding start documentCount={}", relevantDocuments.size());
        List<float[]> embeddings = embedTexts(relevantDocuments.stream().map(Document::getText).toList());
        log.info("RAG context embedding finished embeddingCount={} elapsedMs={}",
                embeddings.size(), elapsedMs(embeddingStarted));
        List<Document> refinedDocuments = new ArrayList<>();
        List<float[]> refinedEmbeddings = new ArrayList<>();
        for (int i = 0; i < relevantDocuments.size(); i++) {
            Document document = relevantDocuments.get(i);
            float[] embedding = embeddings.get(i);
            if (isDuplicate(embedding, refinedEmbeddings)) {
                continue;
            }
            refinedDocuments.add(document);
            refinedEmbeddings.add(embedding);
            if (refinedDocuments.size() >= maxChunks) {
                break;
            }
        }
        log.info("RAG context deduplication finished relevantCount={} outputCount={} elapsedMs={}",
                relevantDocuments.size(), refinedDocuments.size(), elapsedMs(started));
        return refinedDocuments;
    }

    private boolean isRelevant(Document document) {
        return vectorScore(document) >= relevanceSimilarityThreshold;
    }

    private boolean isDuplicate(float[] embedding, List<float[]> refinedEmbeddings) {
        for (float[] refinedEmbedding : refinedEmbeddings) {
            if (cosineSimilarity(embedding, refinedEmbedding) >= duplicateSimilarityThreshold) {
                return true;
            }
        }
        return false;
    }

    private List<float[]> embedTexts(List<String> texts) {
        List<float[]> embeddings = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += MAX_EMBEDDING_BATCH_SIZE) {
            int end = Math.min(start + MAX_EMBEDDING_BATCH_SIZE, texts.size());
            long batchStarted = System.nanoTime();
            log.info("RAG context embedding batch start batchStart={} batchEnd={} batchSize={}",
                    start, end, end - start);
            embeddings.addAll(embeddingModel.embed(texts.subList(start, end)));
            log.info("RAG context embedding batch finished batchStart={} batchEnd={} elapsedMs={}",
                    start, end, elapsedMs(batchStarted));
        }
        return embeddings;
    }

    private double vectorScore(Document document) {
        Object vectorScore = document.getMetadata().get("vector_score");
        if (vectorScore instanceof Number number) {
            return number.doubleValue();
        }
        if (vectorScore != null) {
            try {
                return Double.parseDouble(String.valueOf(vectorScore));
            } catch (NumberFormatException ignored) {
                return score(document);
            }
        }
        return score(document);
    }

    private double score(Document document) {
        return document.getScore() == null ? 0 : document.getScore();
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
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
}
