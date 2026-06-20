package com.example.rag.service;

import com.example.rag.dto.RagDtos.SearchHit;
import com.example.rag.service.RagMetadataStore.ChunkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RagSearchService {
    private static final Logger log = LoggerFactory.getLogger(RagSearchService.class);
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]|[A-Za-z0-9_\\-]+");
    private static final double MIN_KEYWORD_SCORE = 0.000001;

    private final VectorStore vectorStore;
    private final RagQueryOptimizerService queryOptimizerService;
    private final RagMetadataStore metadataStore;
    private final boolean hybridEnabled;
    private final boolean keywordEnabled;
    private final boolean rerankEnabled;
    private final double similarityThreshold;
    private final int candidateMultiplier;
    private final int rrfK;
    private final double vectorWeight;
    private final double keywordWeight;
    private final double rerankVectorWeight;
    private final double rerankKeywordWeight;
    private final double rerankLexicalWeight;
    private final double rerankExactPhraseWeight;

    public RagSearchService(VectorStore vectorStore,
                            RagQueryOptimizerService queryOptimizerService,
                            RagMetadataStore metadataStore,
                            @Value("${rag.retrieval.hybrid.enabled:true}") boolean hybridEnabled,
                            @Value("${rag.retrieval.keyword.enabled:true}") boolean keywordEnabled,
                            @Value("${rag.retrieval.rerank.enabled:true}") boolean rerankEnabled,
                            @Value("${rag.retrieval.vector.similarity-threshold:0.55}") double similarityThreshold,
                            @Value("${rag.retrieval.candidate-multiplier:4}") int candidateMultiplier,
                            @Value("${rag.retrieval.rrf-k:60}") int rrfK,
                            @Value("${rag.retrieval.weights.vector:0.65}") double vectorWeight,
                            @Value("${rag.retrieval.weights.keyword:0.35}") double keywordWeight,
                            @Value("${rag.retrieval.rerank.weights.vector:0.50}") double rerankVectorWeight,
                            @Value("${rag.retrieval.rerank.weights.keyword:0.30}") double rerankKeywordWeight,
                            @Value("${rag.retrieval.rerank.weights.lexical:0.15}") double rerankLexicalWeight,
                            @Value("${rag.retrieval.rerank.weights.exact-phrase:0.05}") double rerankExactPhraseWeight) {
        this.vectorStore = vectorStore;
        this.queryOptimizerService = queryOptimizerService;
        this.metadataStore = metadataStore;
        this.hybridEnabled = hybridEnabled;
        this.keywordEnabled = keywordEnabled;
        this.rerankEnabled = rerankEnabled;
        this.similarityThreshold = similarityThreshold;
        this.candidateMultiplier = Math.max(1, candidateMultiplier);
        this.rrfK = Math.max(1, rrfK);
        this.vectorWeight = Math.max(0, vectorWeight);
        this.keywordWeight = Math.max(0, keywordWeight);
        this.rerankVectorWeight = Math.max(0, rerankVectorWeight);
        this.rerankKeywordWeight = Math.max(0, rerankKeywordWeight);
        this.rerankLexicalWeight = Math.max(0, rerankLexicalWeight);
        this.rerankExactPhraseWeight = Math.max(0, rerankExactPhraseWeight);
    }

    public List<Document> retrieve(String departmentId, String moduleCode, String question, int topK) {
        long started = System.nanoTime();
        log.info("RAG retrieval start departmentId={} moduleCode={} topK={} questionLength={}",
                departmentId, moduleCode, topK, length(question));
        long optimizeStarted = System.nanoTime();
        log.info("RAG query optimization start departmentId={} moduleCode={}", departmentId, moduleCode);
        List<String> queries = queryOptimizerService.optimize(question);
        log.info("RAG query optimization finished departmentId={} moduleCode={} queryCount={} elapsedMs={}",
                departmentId, moduleCode, queries.size(), elapsedMs(optimizeStarted));
        List<Document> documents = retrieveByQueries(departmentId, moduleCode, queries, question, topK);
        log.info("RAG retrieval finished departmentId={} moduleCode={} resultCount={} elapsedMs={}",
                departmentId, moduleCode, documents.size(), elapsedMs(started));
        return documents;
    }

    public List<Document> retrieveByQueries(String departmentId, String moduleCode, List<String> queries, int topK) {
        return retrieveByQueries(departmentId, moduleCode, queries, queries == null || queries.isEmpty() ? "" : queries.get(0), topK);
    }

    public List<Document> retrieveByQueries(String departmentId, String moduleCode, List<String> queries,
                                            String originalQuestion, int topK) {
        long started = System.nanoTime();
        String safeDepartmentId = safe(departmentId);
        String safeModuleCode = safe(moduleCode);
        int finalTopK = Math.max(1, topK);
        int candidateTopK = finalTopK * candidateMultiplier;
        List<String> retrievalQueries = normalizeQueries(queries);
        log.info("RAG retrieval query normalization finished departmentId={} moduleCode={} inputQueryCount={} normalizedQueryCount={} finalTopK={} candidateTopK={}",
                safeDepartmentId, safeModuleCode, queries == null ? 0 : queries.size(), retrievalQueries.size(),
                finalTopK, candidateTopK);
        if (retrievalQueries.isEmpty()) {
            log.info("RAG retrieval skipped departmentId={} moduleCode={} reason=no_queries elapsedMs={}",
                    safeDepartmentId, safeModuleCode, elapsedMs(started));
            return List.of();
        }

        String filter = "department_id == '" + safeDepartmentId + "' && module_code == '"
                + safeModuleCode + "' && status == 'ACTIVE'";
        LinkedHashMap<String, Candidate> candidates = new LinkedHashMap<>();
        for (String query : retrievalQueries) {
            long vectorStarted = System.nanoTime();
            log.info("RAG vector search start departmentId={} moduleCode={} candidateTopK={} similarityThreshold={} queryLength={}",
                    safeDepartmentId, safeModuleCode, candidateTopK, similarityThreshold, length(query));
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(candidateTopK)
                    .similarityThreshold(similarityThreshold)
                    .filterExpression(filter)
                    .build());
            mergeVectorCandidates(candidates, docs);
            log.info("RAG vector search finished departmentId={} moduleCode={} hitCount={} candidateCount={} elapsedMs={}",
                    safeDepartmentId, safeModuleCode, docs.size(), candidates.size(), elapsedMs(vectorStarted));
        }
        if (hybridEnabled && keywordEnabled) {
            int beforeKeywordCount = candidates.size();
            long keywordStarted = System.nanoTime();
            log.info("RAG keyword search start departmentId={} moduleCode={} queryCount={} candidateTopK={}",
                    safeDepartmentId, safeModuleCode, retrievalQueries.size(), candidateTopK);
            mergeKeywordCandidates(candidates, safeDepartmentId, safeModuleCode, retrievalQueries, candidateTopK);
            log.info("RAG keyword search finished departmentId={} moduleCode={} candidateCountBefore={} candidateCountAfter={} elapsedMs={}",
                    safeDepartmentId, safeModuleCode, beforeKeywordCount, candidates.size(), elapsedMs(keywordStarted));
        } else {
            log.info("RAG keyword search skipped departmentId={} moduleCode={} hybridEnabled={} keywordEnabled={}",
                    safeDepartmentId, safeModuleCode, hybridEnabled, keywordEnabled);
        }

        List<String> rerankTerms = tokenize(originalQuestion).isEmpty()
                ? tokenize(String.join(" ", retrievalQueries))
                : tokenize(originalQuestion);
        long rerankStarted = System.nanoTime();
        log.info("RAG rerank start departmentId={} moduleCode={} candidateCount={} rerankEnabled={} rerankTermCount={}",
                safeDepartmentId, safeModuleCode, candidates.size(), rerankEnabled, rerankTerms.size());
        List<Document> rankedDocuments = candidates.values().stream()
                .map(candidate -> rankCandidate(candidate, rerankTerms))
                .sorted(Comparator.comparingDouble(RankedCandidate::score).reversed()
                        .thenComparing(candidate -> str(candidate.document().getMetadata().get("file_name")),
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(candidate -> integer(candidate.document().getMetadata().get("chunk_index")),
                                Comparator.nullsLast(Integer::compareTo)))
                .limit(finalTopK)
                .map(RankedCandidate::document)
                .toList();
        log.info("RAG rerank finished departmentId={} moduleCode={} resultCount={} elapsedMs={}",
                safeDepartmentId, safeModuleCode, rankedDocuments.size(), elapsedMs(rerankStarted));
        log.info("RAG retrieval pipeline finished departmentId={} moduleCode={} resultCount={} elapsedMs={}",
                safeDepartmentId, safeModuleCode, rankedDocuments.size(), elapsedMs(started));
        return rankedDocuments;
    }

    public List<SearchHit> search(String departmentId, String moduleCode, String question, int topK) {
        return toHits(retrieve(departmentId, moduleCode, question, topK));
    }

    public List<SearchHit> toHits(List<Document> docs) {
        return docs.stream().map(this::toHit).toList();
    }

    public SearchHit toHit(Document doc) {
        Map<String, Object> metadata = doc.getMetadata();
        return new SearchHit(doc.getText(), str(metadata.get("doc_id")), str(metadata.get("file_name")),
                str(metadata.get("department_id")), str(metadata.get("module_code")),
                integer(metadata.get("chunk_index")), doc.getScore());
    }

    private List<String> normalizeQueries(List<String> queries) {
        if (queries == null) {
            return List.of();
        }
        return queries.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private void mergeVectorCandidates(Map<String, Candidate> candidates, List<Document> docs) {
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            String key = docKey(doc);
            Candidate candidate = candidates.computeIfAbsent(key, ignored -> Candidate.from(doc));
            if (score(doc) > candidate.vectorScore) {
                candidate.document = doc;
                candidate.vectorScore = score(doc);
            }
            candidate.bestVectorRank = Math.min(candidate.bestVectorRank, i + 1);
        }
    }

    private void mergeKeywordCandidates(Map<String, Candidate> candidates, String departmentId, String moduleCode,
                                        List<String> queries, int candidateTopK) {
        List<ChunkRecord> chunks = metadataStore.listActiveChunks(departmentId, moduleCode);
        log.info("RAG keyword active chunks loaded departmentId={} moduleCode={} activeChunkCount={}",
                departmentId, moduleCode, chunks.size());
        if (chunks.isEmpty()) {
            return;
        }

        Map<String, List<String>> chunkTokens = new HashMap<>();
        Map<String, Integer> documentFrequencies = new HashMap<>();
        long totalTokenCount = 0;
        for (ChunkRecord chunk : chunks) {
            List<String> tokens = tokenize(chunk.content());
            chunkTokens.put(chunk.chunkId(), tokens);
            totalTokenCount += tokens.size();
            new HashSet<>(tokens).forEach(token -> documentFrequencies.merge(token, 1, Integer::sum));
        }
        double avgDocumentLength = Math.max(1.0, (double) totalTokenCount / Math.max(1, chunks.size()));

        Map<String, KeywordMatch> bestMatches = new HashMap<>();
        for (String query : queries) {
            List<String> queryTokens = tokenize(query);
            if (queryTokens.isEmpty()) {
                continue;
            }
            for (ChunkRecord chunk : chunks) {
                double keywordScore = bm25(queryTokens, chunkTokens.getOrDefault(chunk.chunkId(), List.of()),
                        documentFrequencies, chunks.size(), avgDocumentLength);
                if (keywordScore <= MIN_KEYWORD_SCORE) {
                    continue;
                }
                KeywordMatch existing = bestMatches.get(chunk.chunkId());
                if (existing == null || keywordScore > existing.score()) {
                    bestMatches.put(chunk.chunkId(), new KeywordMatch(chunk, keywordScore));
                }
            }
        }

        List<KeywordMatch> rankedMatches = bestMatches.values().stream()
                .sorted(Comparator.comparingDouble(KeywordMatch::score).reversed())
                .limit(candidateTopK)
                .toList();
        log.info("RAG keyword ranking finished departmentId={} moduleCode={} matchCount={} limitedMatchCount={}",
                departmentId, moduleCode, bestMatches.size(), rankedMatches.size());
        double maxKeywordScore = rankedMatches.stream()
                .mapToDouble(KeywordMatch::score)
                .max()
                .orElse(1);
        for (int i = 0; i < rankedMatches.size(); i++) {
            KeywordMatch match = rankedMatches.get(i);
            Candidate candidate = candidates.computeIfAbsent(match.chunk().chunkId(),
                    ignored -> Candidate.from(toDocument(match.chunk())));
            candidate.keywordScore = Math.max(candidate.keywordScore, match.score() / maxKeywordScore);
            candidate.bestKeywordRank = Math.min(candidate.bestKeywordRank, i + 1);
        }
    }

    private RankedCandidate rankCandidate(Candidate candidate, List<String> rerankTerms) {
        double fusedScore = 0;
        if (candidate.bestVectorRank < Integer.MAX_VALUE) {
            fusedScore += vectorWeight / (rrfK + candidate.bestVectorRank);
        }
        if (candidate.bestKeywordRank < Integer.MAX_VALUE) {
            fusedScore += keywordWeight / (rrfK + candidate.bestKeywordRank);
        }
        double normalizedVectorScore = clamp01(candidate.vectorScore);
        double lexicalScore = lexicalOverlap(rerankTerms, tokenize(candidate.document.getText()));
        double exactPhraseScore = exactPhraseScore(rerankTerms, candidate.document.getText());
        double rerankScore = rerankEnabled
                ? rerankVectorWeight * normalizedVectorScore
                + rerankKeywordWeight * candidate.keywordScore
                + rerankLexicalWeight * lexicalScore
                + rerankExactPhraseWeight * exactPhraseScore
                : 0;
        double finalScore = fusedScore + rerankScore;

        Document rankedDocument = candidate.document.mutate()
                .score(finalScore)
                .metadata("vector_score", candidate.vectorScore)
                .metadata("keyword_score", candidate.keywordScore)
                .metadata("hybrid_score", fusedScore)
                .metadata("rerank_score", rerankScore)
                .build();
        return new RankedCandidate(rankedDocument, finalScore);
    }

    private double bm25(List<String> queryTokens, List<String> documentTokens,
                       Map<String, Integer> documentFrequencies, int documentCount, double avgDocumentLength) {
        if (queryTokens.isEmpty() || documentTokens.isEmpty()) {
            return 0;
        }
        Map<String, Integer> termFrequencies = frequencies(documentTokens);
        double k1 = 1.5;
        double b = 0.75;
        double score = 0;
        for (String token : new LinkedHashSet<>(queryTokens)) {
            int tf = termFrequencies.getOrDefault(token, 0);
            if (tf == 0) {
                continue;
            }
            int df = documentFrequencies.getOrDefault(token, 0);
            double idf = Math.log(1 + (documentCount - df + 0.5) / (df + 0.5));
            double denominator = tf + k1 * (1 - b + b * documentTokens.size() / avgDocumentLength);
            score += idf * (tf * (k1 + 1)) / denominator;
        }
        return score;
    }

    private double lexicalOverlap(List<String> queryTokens, List<String> documentTokens) {
        if (queryTokens.isEmpty() || documentTokens.isEmpty()) {
            return 0;
        }
        Set<String> queryTokenSet = new HashSet<>(queryTokens);
        Set<String> documentTokenSet = new HashSet<>(documentTokens);
        long matches = queryTokenSet.stream()
                .filter(documentTokenSet::contains)
                .count();
        return (double) matches / queryTokenSet.size();
    }

    private double exactPhraseScore(List<String> queryTokens, String content) {
        if (queryTokens.isEmpty() || !StringUtils.hasText(content)) {
            return 0;
        }
        String normalizedContent = content.toLowerCase();
        long eligible = queryTokens.stream().filter(token -> token.length() >= 2).distinct().count();
        if (eligible == 0) {
            return 0;
        }
        long matches = queryTokens.stream()
                .filter(token -> token.length() >= 2)
                .filter(normalizedContent::contains)
                .distinct()
                .count();
        return (double) matches / eligible;
    }

    private Map<String, Integer> frequencies(Collection<String> tokens) {
        Map<String, Integer> frequencies = new HashMap<>();
        for (String token : tokens) {
            frequencies.merge(token, 1, Integer::sum);
        }
        return frequencies;
    }

    private List<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase());
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (!token.isEmpty() && !isStopWord(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private boolean isStopWord(String token) {
        return token.length() == 1 && "的了和与及或在是为对中有将把按从到".contains(token);
    }

    private Document toDocument(ChunkRecord chunk) {
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
        return Document.builder()
                .id(chunk.chunkId())
                .text(chunk.content())
                .metadata(metadata)
                .score(0.0)
                .build();
    }

    private String docKey(Document doc) {
        Object chunkId = doc.getMetadata().get("chunk_id");
        if (chunkId != null) {
            return String.valueOf(chunkId);
        }
        if (StringUtils.hasText(doc.getId())) {
            return doc.getId();
        }
        return str(doc.getMetadata().get("doc_id")) + "#" + str(doc.getMetadata().get("chunk_index"));
    }

    private double score(Document doc) {
        return doc.getScore() == null ? 0 : doc.getScore();
    }

    private double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0;
        }
        return Math.max(0, Math.min(1, value));
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    private String safe(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid department or module code");
        }
        return value;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integer(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(value));
    }

    private static class Candidate {
        private Document document;
        private double vectorScore;
        private double keywordScore;
        private int bestVectorRank = Integer.MAX_VALUE;
        private int bestKeywordRank = Integer.MAX_VALUE;

        private static Candidate from(Document document) {
            Candidate candidate = new Candidate();
            candidate.document = document;
            candidate.vectorScore = document.getScore() == null ? 0 : document.getScore();
            return candidate;
        }
    }

    private record KeywordMatch(ChunkRecord chunk, double score) {
    }

    private record RankedCandidate(Document document, double score) {
    }
}
