package com.example.rag.service;

import com.example.rag.mapper.ChatMemoryMapper;
import com.example.rag.model.ChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatMemoryService {
    private static final Logger log = LoggerFactory.getLogger(ChatMemoryService.class);
    private static final int MAX_MEMORY_COUNT = 30;
    private static final int MAX_MEMORY_CONTEXT_CHARS = 12_000;
    private static final int MAX_SUMMARY_SOURCE_CHARS = 24_000;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ChatMemoryMapper memoryMapper;
    private final ChatClient chatClient;

    public ChatMemoryService(ChatMemoryMapper memoryMapper,
                             @Autowired @Qualifier("chatModel") ChatModel chatModel) {
        this.memoryMapper = memoryMapper;
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    public String renderMemoryContext(String sessionId) {
        if (isBlank(sessionId)) {
            return "";
        }
        List<ChatMemory> memories = memoryMapper.findBySessionId(sessionId.trim());
        if (memories.isEmpty()) {
            return "";
        }
        String context = memories.stream()
                .map(this::formatMemory)
                .collect(Collectors.joining("\n\n"));
        return limit(context, MAX_MEMORY_CONTEXT_CHARS);
    }

    public void recordTurn(String sessionId, String question, String answer) {
        if (isBlank(sessionId)) {
            log.warn("Skip chat memory persistence because sessionId is blank");
            return;
        }
        String normalizedSessionId = sessionId.trim();
        ChatMemory memory = new ChatMemory();
        memory.setSessionId(normalizedSessionId);
        memory.setMemoryType("TURN");
        memory.setQuestion(question);
        memory.setAnswer(answer);
        memory.setContent(formatTurnContent(question, answer));
        memoryMapper.insert(memory);

        int count = memoryMapper.countBySessionId(normalizedSessionId);
        if (count > MAX_MEMORY_COUNT) {
            summarizeSessionMemories(normalizedSessionId);
        }
    }

    private void summarizeSessionMemories(String sessionId) {
        List<ChatMemory> memories = memoryMapper.findBySessionId(sessionId);
        if (memories.size() <= MAX_MEMORY_COUNT) {
            return;
        }
        String source = limit(memories.stream()
                .map(this::formatMemory)
                .collect(Collectors.joining("\n\n")), MAX_SUMMARY_SOURCE_CHARS);
        String prompt = """
                Summarize the following chat memories into one durable memory for future RAG answers.
                Preserve user preferences, confirmed facts, decisions, constraints, and unresolved issues.
                Remove duplicated wording and temporary details. Keep it concise but specific.

                Memories:
                %s
                """.formatted(source);
        String summary = chatClient.prompt()
                .user(prompt)
                .call()
                .content();
        if (isBlank(summary)) {
            log.warn("Skip chat memory compression because model returned blank summary sessionId={}", sessionId);
            return;
        }

        ChatMemory compressed = new ChatMemory();
        compressed.setSessionId(sessionId);
        compressed.setMemoryType("SUMMARY");
        compressed.setContent(summary.trim());
        memoryMapper.deleteBySessionId(sessionId);
        memoryMapper.insert(compressed);
        log.info("Compressed chat memories sessionId={} originalCount={} summaryLength={}",
                sessionId, memories.size(), compressed.getContent().length());
    }

    private String formatMemory(ChatMemory memory) {
        String createdAt = memory.getCreatedAt() == null ? "-" : DATE_TIME_FORMATTER.format(memory.getCreatedAt());
        return "[%s at %s]\n%s".formatted(memory.getMemoryType(), createdAt, memory.getContent());
    }

    private String formatTurnContent(String question, String answer) {
        return """
                User question:
                %s

                Assistant answer:
                %s
                """.formatted(nullToEmpty(question), nullToEmpty(answer)).trim();
    }

    private String limit(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "\n...[truncated]";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
