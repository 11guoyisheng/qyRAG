package com.example.rag.controller;

import com.example.rag.dto.RagDtos.*;
import com.example.rag.service.RagChatService;
import com.example.rag.service.RagDocumentService;
import com.example.rag.service.RagMetadataStore;
import com.example.rag.service.RagSearchService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/rag")
@CrossOrigin
public class RagController {
    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagDocumentService documentService;
    private final RagMetadataStore metadataStore;
    private final RagSearchService searchService;
    private final RagChatService chatService;
    private final ObjectMapper objectMapper;

    public RagController(RagDocumentService documentService, RagMetadataStore metadataStore,
                         RagSearchService searchService, RagChatService chatService, ObjectMapper objectMapper) {
        this.documentService = documentService;
        this.metadataStore = metadataStore;
        this.searchService = searchService;
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/documents")
    public Mono<UploadResult> upload(@RequestPart("file") FilePart file,
                                     @RequestPart("departmentId") String departmentId,
                                     @RequestPart("moduleCode") String moduleCode,
                                     @RequestPart(value = "uploadedBy", required = false) String uploadedBy) {
        uploadedBy = uploadedBy == null || uploadedBy.isBlank() ? "system" : uploadedBy;
        return documentService.upload(file, departmentId, moduleCode, uploadedBy);
    }

    @PostMapping("/documents/manual")
    public UploadResult uploadManual(@RequestBody ManualDocumentRequest request) {
        return documentService.uploadManual(request);
    }

    @GetMapping("/documents")
    public List<DocumentSummary> documents(@RequestParam(value = "departmentId", required = false) String departmentId,
                                           @RequestParam(value = "moduleCode", required = false) String moduleCode) {
        return metadataStore.listDocuments(departmentId, moduleCode);
    }

    @DeleteMapping("/documents/{documentId}")
    public void deleteDocument(@PathVariable("documentId") String documentId) {
        documentService.deleteDocument(documentId);
    }

    @GetMapping("/documents/{documentId}/chunks")
    public List<ChunkDto> chunks(@PathVariable("documentId") String documentId) {
        return metadataStore.listChunks(documentId);
    }

    @PostMapping("/documents/{documentId}/chunks")
    public ChunkDto addChunk(@PathVariable("documentId") String documentId, @RequestBody AddChunkRequest request) {
        return documentService.addChunk(documentId, request.content()).toDto();
    }

    @PutMapping("/chunks/{chunkId}")
    public ChunkDto updateChunk(@PathVariable("chunkId") String chunkId, @RequestBody UpdateChunkRequest request) {
        return documentService.updateChunk(chunkId, request.content(), request.status()).toDto();
    }

    @DeleteMapping("/chunks/{chunkId}")
    public void deleteChunk(@PathVariable("chunkId") String chunkId) {
        documentService.deleteChunk(chunkId);
    }

    @GetMapping("/departments")
    public List<DepartmentDto> departments() {
        return metadataStore.listDepartments();
    }

    @PostMapping("/departments")
    public DepartmentDto upsertDepartment(@RequestBody UpsertDepartmentRequest request) {
        return metadataStore.upsertDepartment(request);
    }

    @DeleteMapping("/departments/{departmentId}")
    public void deleteDepartment(@PathVariable("departmentId") String departmentId) {
        metadataStore.deleteDepartment(departmentId);
    }

    @GetMapping("/modules")
    public List<ModuleDto> modules(@RequestParam(value = "departmentId", required = false) String departmentId) {
        return metadataStore.listModules(departmentId);
    }

    @PostMapping("/modules")
    public ModuleDto upsertModule(@RequestBody UpsertModuleRequest request) {
        return metadataStore.upsertModule(request);
    }

    @DeleteMapping("/departments/{departmentId}/modules/{moduleCode}")
    public void deleteModule(@PathVariable("departmentId") String departmentId,
                             @PathVariable("moduleCode") String moduleCode) {
        metadataStore.deleteModule(departmentId, moduleCode);
    }

    @PostMapping("/search")
    public List<SearchHit> search(@RequestBody SearchRequest request) {
        long started = System.nanoTime();
        int topK = request.topK() == null ? 5 : request.topK();
        log.info("RAG search API call start departmentId={} moduleCode={} topK={} questionLength={}",
                request.departmentId(), request.moduleCode(), topK, length(request.question()));
        try {
            List<SearchHit> hits = searchService.search(
                    request.departmentId(), request.moduleCode(), request.question(), topK);
            log.info("RAG search API call finished departmentId={} moduleCode={} topK={} resultCount={} elapsedMs={}",
                    request.departmentId(), request.moduleCode(), topK, hits.size(), elapsedMs(started));
            return hits;
        } catch (RuntimeException ex) {
            log.error("RAG search API call failed departmentId={} moduleCode={} topK={} elapsedMs={}",
                    request.departmentId(), request.moduleCode(), topK, elapsedMs(started), ex);
            throw ex;
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest request) {
        long started = System.nanoTime();
        log.info("RAG chat stream API call start departmentId={} moduleCode={} questionLength={}",
                request.departmentId(), request.moduleCode(), length(request.question()));
        try {
            RagChatService.ChatStream chatStream = chatService.streamChat(
                    request.departmentId(), request.moduleCode(), request.sessionId(), request.question());
            log.info("RAG chat stream API call prepared departmentId={} moduleCode={} sourceCount={} elapsedMs={}",
                    request.departmentId(), request.moduleCode(), chatStream.sources().size(), elapsedMs(started));

            ServerSentEvent<String> sourcesEvent = ServerSentEvent.builder(toJson(chatStream.sources()))
                    .event("sources")
                    .build();
            return Flux.just(sourcesEvent)
                    .concatWith(chatStream.content()
                            .map(token -> ServerSentEvent.builder(token).event("message").build())
                            .concatWithValues(ServerSentEvent.builder("[DONE]").event("done").build()))
                    .doOnSubscribe(subscription -> log.info(
                            "RAG chat stream SSE subscribed departmentId={} moduleCode={} sourceCount={}",
                            request.departmentId(), request.moduleCode(), chatStream.sources().size()))
                    .doOnError(ex -> log.error(
                            "RAG chat stream API call failed departmentId={} moduleCode={} elapsedMs={}",
                            request.departmentId(), request.moduleCode(), elapsedMs(started), ex))
                    .doFinally(signalType -> log.info(
                            "RAG chat stream API call finished departmentId={} moduleCode={} signal={} sourceCount={} elapsedMs={}",
                            request.departmentId(), request.moduleCode(), signalType, chatStream.sources().size(),
                            elapsedMs(started)));
        } catch (RuntimeException ex) {
            log.error("RAG chat stream API call failed before streaming departmentId={} moduleCode={} elapsedMs={}",
                    request.departmentId(), request.moduleCode(), elapsedMs(started), ex);
            throw ex;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize SSE data", e);
        }
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
