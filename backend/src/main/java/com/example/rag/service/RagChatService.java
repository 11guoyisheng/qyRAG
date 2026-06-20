package com.example.rag.service;

import com.example.rag.dto.RagDtos.SearchHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagChatService {
    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);
    private static final String NO_CLEAR_EVIDENCE_MESSAGE = "当前知识库中没有找到明确依据。";

    private final ChatClient chatClient;
    private final RagSearchService searchService;
    private final RagContextPostProcessorService contextPostProcessorService;
    private final ChatMemoryService chatMemoryService;
    private final double minAnswerVectorScore;

    public record ChatStream(List<SearchHit> sources, Flux<String> content) {}

    public RagChatService(@Autowired @Qualifier("chatModel") ChatModel chatModel,
                          RagSearchService searchService,
                          RagContextPostProcessorService contextPostProcessorService,
                          ChatMemoryService chatMemoryService,
                          @Value("${rag.answer.min-vector-score:0.62}") double minAnswerVectorScore) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.searchService = searchService;
        this.contextPostProcessorService = contextPostProcessorService;
        this.chatMemoryService = chatMemoryService;
        this.minAnswerVectorScore = minAnswerVectorScore;
    }

    public ChatStream streamChat(String departmentId, String moduleCode, String sessionId, String question) {
        long chatStarted = System.nanoTime();
        log.info("RAG chat start departmentId={} moduleCode={} sessionId={} questionLength={}",
                departmentId, moduleCode, sessionId, length(question));

        long retrieveStarted = System.nanoTime();
        log.info("RAG chat retrieval start departmentId={} moduleCode={}", departmentId, moduleCode);
        List<Document> retrievedDocs = searchService.retrieve(departmentId, moduleCode, question, 12);
        log.info("RAG chat retrieval finished departmentId={} moduleCode={} retrievedCount={} elapsedMs={}",
                departmentId, moduleCode, retrievedDocs.size(), elapsedMs(retrieveStarted));

        long refineStarted = System.nanoTime();
        log.info("RAG chat context post-processing start retrievedCount={}", retrievedDocs.size());
        List<Document> docs = contextPostProcessorService.refine(retrievedDocs);
        log.info("RAG chat context post-processing finished refinedCount={} elapsedMs={}",
                docs.size(), elapsedMs(refineStarted));
        if (docs.isEmpty()) {
            log.info("RAG chat refused reason=no_refined_documents departmentId={} moduleCode={} elapsedMs={}",
                    departmentId, moduleCode, elapsedMs(chatStarted));
            return new ChatStream(List.of(), Flux.just(NO_CLEAR_EVIDENCE_MESSAGE));
        }
        double maxVectorScore = docs.stream()
                .mapToDouble(this::vectorScore)
                .max()
                .orElse(0);
        if (maxVectorScore < minAnswerVectorScore) {
            log.info("RAG chat refused reason=low_vector_score departmentId={} moduleCode={} maxVectorScore={} minAnswerVectorScore={} elapsedMs={}",
                    departmentId, moduleCode, maxVectorScore, minAnswerVectorScore, elapsedMs(chatStarted));
            return new ChatStream(searchService.toHits(docs), Flux.just(NO_CLEAR_EVIDENCE_MESSAGE));
        }

        long promptStarted = System.nanoTime();
        log.info("RAG chat prompt assembly start sourceCount={}", docs.size());
        String context = docs.stream()
                .map(doc -> "[source:" + doc.getMetadata().get("file_name") + ", chunk:"
                        + doc.getMetadata().get("chunk_index") + "]\n" + doc.getText())
                .collect(Collectors.joining("\n\n---\n\n"));
        String memoryContext = chatMemoryService.renderMemoryContext(sessionId);

        String prompt = """
                你是企业内部知识库助手，必须严格遵守以下规则：

                1. 只能依据【知识库资料】回答【问题】。
                2. 【会话记忆】只能用于理解上下文、用户偏好和历史指代，不能作为制度依据。
                3. 如果【知识库资料】没有直接、明确、有效的依据，必须原样回答：
                当前知识库中没有找到明确依据。
                4. 如果资料中出现“错误说法”“已废止流程”“错误操作案例”“不得作为依据”“仅供培训反例”等内容，不能把它当作当前制度或操作依据。
                5. 用户询问当前制度、当前流程、能不能做某事时，优先采用有效制度、操作手册、使用说明中的正确信息。
                6. 不要根据常识、经验或猜测补充资料中没有的信息。
                7. 回答必须使用中文，简洁准确；涉及流程时按步骤输出。
                8. 尽量保留资料中的关键表述、数字、期限、角色名称和限制条件。

                【会话记忆】
                %s

                【问题】
                %s

                【知识库资料】
                %s
                
                注意：
                1、必须基于上下文
                2、不能编造
                3、不知道就说不知道
     
                """.formatted(blankToNone(memoryContext), question, context);
        log.info("RAG chat prompt assembly finished sourceCount={} memoryLength={} contextLength={} promptLength={} elapsedMs={}",
                docs.size(), memoryContext.length(), context.length(), prompt.length(), elapsedMs(promptStarted));

        StringBuilder answerBuffer = new StringBuilder();
        Flux<String> content = chatClient.prompt()
                .user(prompt)
                .stream()
                .content()
                .doOnNext(answerBuffer::append)
                .doOnComplete(() -> persistMemoryAsync(sessionId, question, answerBuffer.toString()))
                .doOnSubscribe(subscription -> log.info(
                        "RAG chat model stream subscribed departmentId={} moduleCode={} sourceCount={}",
                        departmentId, moduleCode, docs.size()))
                .doOnError(ex -> log.error(
                        "RAG chat model stream failed departmentId={} moduleCode={} elapsedMs={}",
                        departmentId, moduleCode, elapsedMs(chatStarted), ex))
                .doFinally(signalType -> log.info(
                        "RAG chat finished departmentId={} moduleCode={} signal={} sourceCount={} elapsedMs={}",
                        departmentId, moduleCode, signalType, docs.size(), elapsedMs(chatStarted)));
        log.info("RAG chat model stream prepared departmentId={} moduleCode={}", departmentId, moduleCode);
        return new ChatStream(searchService.toHits(docs), content);
    }

    private void persistMemoryAsync(String sessionId, String question, String answer) {
        Mono.fromRunnable(() -> chatMemoryService.recordTurn(sessionId, question, answer))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        ignored -> {
                        },
                        ex -> log.error("Failed to persist chat memory sessionId={} questionLength={} answerLength={}",
                                sessionId, length(question), length(answer), ex));
    }

    private String blankToNone(String value) {
        return value == null || value.isBlank() ? "(none)" : value;
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

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
