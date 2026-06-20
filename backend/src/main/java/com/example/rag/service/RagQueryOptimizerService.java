package com.example.rag.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class RagQueryOptimizerService {
    private static final Logger log = LoggerFactory.getLogger(RagQueryOptimizerService.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final boolean queryRewriteEnabled;
    private final boolean hydeEnabled;
    private final boolean multiQueryEnabled;
    private final int maxSubQueries;
    private final int maxHydeChars;

    public RagQueryOptimizerService(@Autowired @Qualifier("chatModel") ChatModel chatModel,
                                    ObjectMapper objectMapper,
                                    @Value("${rag.query-optimization.enabled:true}") boolean enabled,
                                    @Value("${rag.query-optimization.query-rewrite.enabled:true}") boolean queryRewriteEnabled,
                                    @Value("${rag.query-optimization.hyde.enabled:true}") boolean hydeEnabled,
                                    @Value("${rag.query-optimization.multi-query.enabled:true}") boolean multiQueryEnabled,
                                    @Value("${rag.query-optimization.multi-query.max-sub-queries:4}") int maxSubQueries,
                                    @Value("${rag.query-optimization.hyde.max-chars:900}") int maxHydeChars) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.queryRewriteEnabled = queryRewriteEnabled;
        this.hydeEnabled = hydeEnabled;
        this.multiQueryEnabled = multiQueryEnabled;
        this.maxSubQueries = Math.max(1, maxSubQueries);
        this.maxHydeChars = Math.max(200, maxHydeChars);
    }

    public List<String> optimize(String question) {
        long started = System.nanoTime();
        String normalizedQuestion = clean(question);
        if (!enabled || !StringUtils.hasText(normalizedQuestion)) {
            log.info("RAG query optimization skipped enabled={} questionLength={} elapsedMs={}",
                    enabled, normalizedQuestion.length(), elapsedMs(started));
            return single(normalizedQuestion);
        }

        try {
            log.info("RAG query optimization model call start questionLength={} queryRewriteEnabled={} hydeEnabled={} multiQueryEnabled={}",
                    normalizedQuestion.length(), queryRewriteEnabled, hydeEnabled, multiQueryEnabled);
            QueryPlan plan = generatePlan(normalizedQuestion);
            List<String> queries = buildRetrievalQueries(normalizedQuestion, plan);
            log.info("RAG query optimization finished queryCount={} rewriteLength={} hydeLength={} multiQueryCount={} elapsedMs={}",
                    queries.size(), length(plan.queryRewrite()), length(plan.hyde()),
                    plan.multiQuery() == null ? 0 : plan.multiQuery().size(), elapsedMs(started));
            return queries;
        } catch (Exception ex) {
            log.warn("RAG query optimization failed, falling back to original question. elapsedMs={}",
                    elapsedMs(started), ex);
            return single(normalizedQuestion);
        }
    }

    private QueryPlan generatePlan(String question) throws Exception {
        String prompt = """
                你是 RAG 检索前查询优化器。请把用户问题转换为更适合向量检索的候选查询。
                要求：
                1. queryRewrite：将口语化、模糊表达改写为规范检索词，保留原意，不扩展无依据事实。
                2. hyde：生成一段“可能出现在知识库中的假设答案/说明文档”，用于向量检索，不要声称这是真实答案。
                3. multiQuery：如果问题包含多个实体、指标、步骤或条件，拆成最多 %d 个可独立检索的子查询；简单问题可返回空数组。
                只输出严格 JSON，不要 Markdown，不要解释。JSON 结构：
                {"queryRewrite":"...","hyde":"...","multiQuery":["..."]}

                用户问题：
                %s
                """.formatted(maxSubQueries, question);

        long started = System.nanoTime();
        String content = chatClient.prompt()
                .user(prompt)
                .call()
                .content();
        log.info("RAG query optimization model call finished responseLength={} elapsedMs={}",
                length(content), elapsedMs(started));
        return objectMapper.readValue(extractJson(content), QueryPlan.class);
    }

    private List<String> buildRetrievalQueries(String question, QueryPlan plan) {
        Set<String> queries = new LinkedHashSet<>();
        add(queries, question);
        if (queryRewriteEnabled) {
            add(queries, plan.queryRewrite());
        }
        if (hydeEnabled) {
            add(queries, truncate(plan.hyde(), maxHydeChars));
        }
        if (multiQueryEnabled && plan.multiQuery() != null) {
            plan.multiQuery().stream()
                    .limit(maxSubQueries)
                    .forEach(query -> add(queries, query));
        }
        return new ArrayList<>(queries);
    }

    private void add(Set<String> queries, String query) {
        String cleaned = clean(query);
        if (StringUtils.hasText(cleaned)) {
            queries.add(cleaned);
        }
    }

    private List<String> single(String question) {
        return StringUtils.hasText(question) ? List.of(question) : List.of();
    }

    private String extractJson(String content) {
        String cleaned = clean(content);
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return cleaned;
    }

    private String truncate(String value, int maxChars) {
        String cleaned = clean(value);
        if (cleaned.length() <= maxChars) {
            return cleaned;
        }
        return cleaned.substring(0, maxChars);
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replaceAll("(?s)^```(?:json)?\\s*", "")
                .replaceAll("(?s)\\s*```$", "");
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueryPlan(String queryRewrite, String hyde, List<String> multiQuery) {
    }
}
