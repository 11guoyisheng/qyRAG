package com.example.rag.service;

import com.example.rag.dto.RagDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class RagMetadataStore {
    private final ObjectMapper objectMapper;
    private final Path metadataPath;
    private final Object monitor = new Object();
    private MetadataState state;

    public RagMetadataStore(@Value("${rag.metadata-file:./data/rag-metadata.json}") String metadataFile) {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.metadataPath = Path.of(metadataFile);
    }

    public List<DepartmentDto> listDepartments() {
        synchronized (monitor) {
            MetadataState current = load();
            return current.departments.values().stream()
                    .sorted(Comparator.comparing(DepartmentRecord::name))
                    .map(DepartmentRecord::toDto)
                    .toList();
        }
    }

    public DepartmentDto upsertDepartment(UpsertDepartmentRequest request) {
        synchronized (monitor) {
            MetadataState current = load();
            String id = requireCode(request.id(), "部门编码");
            DepartmentRecord record = new DepartmentRecord(id, requireText(request.name(), "部门名称"),
                    textOrEmpty(request.description()));
            current.departments.put(id, record);
            save(current);
            return record.toDto();
        }
    }

    public void deleteDepartment(String departmentId) {
        synchronized (monitor) {
            MetadataState current = load();
            String id = requireCode(departmentId, "部门编码");
            boolean inUse = current.modules.values().stream().anyMatch(module -> id.equals(module.departmentId()))
                    || current.documents.values().stream().anyMatch(document -> id.equals(document.departmentId()));
            if (inUse) {
                throw new IllegalArgumentException("部门已被模块或文档使用，不能删除");
            }
            current.departments.remove(id);
            save(current);
        }
    }

    public List<ModuleDto> listModules(String departmentId) {
        synchronized (monitor) {
            MetadataState current = load();
            return current.modules.values().stream()
                    .filter(module -> !StringUtils.hasText(departmentId) || departmentId.equals(module.departmentId()))
                    .sorted(Comparator.comparing(ModuleRecord::departmentId).thenComparing(ModuleRecord::name))
                    .map(ModuleRecord::toDto)
                    .toList();
        }
    }

    public ModuleDto upsertModule(UpsertModuleRequest request) {
        synchronized (monitor) {
            MetadataState current = load();
            String departmentId = requireCode(request.departmentId(), "部门编码");
            if (!current.departments.containsKey(departmentId)) {
                throw new IllegalArgumentException("部门不存在");
            }
            String code = requireCode(request.code(), "模块编码");
            ModuleRecord record = new ModuleRecord(code, requireText(request.name(), "模块名称"),
                    departmentId, textOrEmpty(request.description()));
            current.modules.put(moduleKey(departmentId, code), record);
            save(current);
            return record.toDto();
        }
    }

    public void deleteModule(String departmentId, String moduleCode) {
        synchronized (monitor) {
            MetadataState current = load();
            String dept = requireCode(departmentId, "部门编码");
            String code = requireCode(moduleCode, "模块编码");
            boolean inUse = current.documents.values().stream()
                    .anyMatch(document -> dept.equals(document.departmentId()) && code.equals(document.moduleCode()));
            if (inUse) {
                throw new IllegalArgumentException("模块已被文档使用，不能删除");
            }
            current.modules.remove(moduleKey(dept, code));
            save(current);
        }
    }

    public void ensureScope(String departmentId, String moduleCode) {
        synchronized (monitor) {
            MetadataState current = load();
            String dept = requireCode(departmentId, "部门编码");
            String code = requireCode(moduleCode, "模块编码");
            current.departments.putIfAbsent(dept, new DepartmentRecord(dept, dept, ""));
            current.modules.putIfAbsent(moduleKey(dept, code), new ModuleRecord(code, code, dept, ""));
            save(current);
        }
    }

    public void saveDocument(DocumentRecord document, List<ChunkRecord> chunks) {
        synchronized (monitor) {
            MetadataState current = load();
            current.documents.put(document.documentId(), document);
            for (ChunkRecord chunk : chunks) {
                current.chunks.put(chunk.chunkId(), chunk);
            }
            save(current);
        }
    }

    public List<DocumentSummary> listDocuments(String departmentId, String moduleCode) {
        synchronized (monitor) {
            MetadataState current = load();
            return current.documents.values().stream()
                    .filter(document -> !StringUtils.hasText(departmentId) || departmentId.equals(document.departmentId()))
                    .filter(document -> !StringUtils.hasText(moduleCode) || moduleCode.equals(document.moduleCode()))
                    .sorted(Comparator.comparing(DocumentRecord::createdAt).reversed())
                    .map(document -> new DocumentSummary(document.documentId(), document.fileName(), document.departmentId(),
                            document.moduleCode(), document.uploadedBy(), document.createdAt(), document.chunkCount()))
                    .toList();
        }
    }

    public Optional<DocumentRecord> findDocument(String documentId) {
        synchronized (monitor) {
            return Optional.ofNullable(load().documents.get(documentId));
        }
    }

    public List<ChunkDto> listChunks(String documentId) {
        synchronized (monitor) {
            MetadataState current = load();
            return current.chunks.values().stream()
                    .filter(chunk -> documentId.equals(chunk.documentId()))
                    .sorted(Comparator.comparing(ChunkRecord::chunkIndex))
                    .map(ChunkRecord::toDto)
                    .toList();
        }
    }

    public List<ChunkRecord> listActiveChunks(String departmentId, String moduleCode) {
        synchronized (monitor) {
            MetadataState current = load();
            return current.chunks.values().stream()
                    .filter(chunk -> departmentId.equals(chunk.departmentId()))
                    .filter(chunk -> moduleCode.equals(chunk.moduleCode()))
                    .filter(chunk -> "ACTIVE".equalsIgnoreCase(chunk.status()))
                    .sorted(Comparator.comparing(ChunkRecord::documentId)
                            .thenComparing(ChunkRecord::chunkIndex, Comparator.nullsLast(Integer::compareTo)))
                    .toList();
        }
    }

    public Optional<ChunkRecord> findChunk(String chunkId) {
        synchronized (monitor) {
            return Optional.ofNullable(load().chunks.get(chunkId));
        }
    }

    public ChunkRecord updateChunk(String chunkId, String content, String status) {
        synchronized (monitor) {
            MetadataState current = load();
            ChunkRecord existing = current.chunks.get(chunkId);
            if (existing == null) {
                throw new NoSuchElementException("分片不存在");
            }
            String normalizedStatus = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : existing.status();
            if (!Set.of("ACTIVE", "DISABLED").contains(normalizedStatus)) {
                throw new IllegalArgumentException("分片状态只能是 ACTIVE 或 DISABLED");
            }
            ChunkRecord updated = existing.withContent(requireText(content, "分片内容"), normalizedStatus, now());
            current.chunks.put(chunkId, updated);
            save(current);
            return updated;
        }
    }

    public ChunkRecord addChunk(String documentId, String content) {
        synchronized (monitor) {
            MetadataState current = load();
            DocumentRecord document = current.documents.get(documentId);
            if (document == null) {
                throw new NoSuchElementException("文档不存在");
            }
            String normalizedContent = requireText(content, "分片内容");
            int nextIndex = current.chunks.values().stream()
                    .filter(chunk -> documentId.equals(chunk.documentId()))
                    .map(ChunkRecord::chunkIndex)
                    .filter(Objects::nonNull)
                    .max(Integer::compareTo)
                    .map(index -> index + 1)
                    .orElse(0);
            String now = now();
            ChunkRecord chunk = new ChunkRecord(documentId + "-" + nextIndex, documentId, nextIndex, normalizedContent,
                    document.fileName(), document.departmentId(), document.moduleCode(), "ACTIVE", now, now);
            DocumentRecord updatedDocument = document.withChunkCount(document.chunkCount() + 1);
            current.documents.put(documentId, updatedDocument);
            current.chunks.put(chunk.chunkId(), chunk);
            save(current);
            return chunk;
        }
    }

    public boolean deleteChunk(String chunkId) {
        synchronized (monitor) {
            MetadataState current = load();
            ChunkRecord removed = current.chunks.remove(chunkId);
            if (removed == null) {
                return false;
            }
            DocumentRecord document = current.documents.get(removed.documentId());
            if (document != null) {
                long remainingCount = current.chunks.values().stream()
                        .filter(chunk -> removed.documentId().equals(chunk.documentId()))
                        .count();
                current.documents.put(document.documentId(), document.withChunkCount((int) remainingCount));
            }
            save(current);
            return true;
        }
    }

    public List<String> deleteDocument(String documentId) {
        synchronized (monitor) {
            MetadataState current = load();
            if (!current.documents.containsKey(documentId)) {
                return List.of();
            }
            List<String> chunkIds = current.chunks.values().stream()
                    .filter(chunk -> documentId.equals(chunk.documentId()))
                    .map(ChunkRecord::chunkId)
                    .toList();
            current.documents.remove(documentId);
            chunkIds.forEach(current.chunks::remove);
            save(current);
            return chunkIds;
        }
    }

    private MetadataState load() {
        if (state != null) {
            return state;
        }
        if (Files.exists(metadataPath)) {
            try {
                state = objectMapper.readValue(metadataPath.toFile(), MetadataState.class);
            } catch (IOException e) {
                throw new IllegalStateException("读取元数据文件失败", e);
            }
        } else {
            state = new MetadataState();
            seedDefaults(state);
            save(state);
        }
        state.normalize();
        return state;
    }

    private void save(MetadataState current) {
        try {
            Files.createDirectories(metadataPath.toAbsolutePath().getParent());
            objectMapper.writeValue(metadataPath.toFile(), current);
        } catch (IOException e) {
            throw new IllegalStateException("保存元数据文件失败", e);
        }
    }

    private void seedDefaults(MetadataState current) {
        current.departments.put("hr", new DepartmentRecord("hr", "人力资源部", "人事制度、考勤和员工流程"));
        current.modules.put(moduleKey("hr", "attendance"), new ModuleRecord("attendance", "考勤管理", "hr", "请假、补卡和考勤异常"));
    }

    private String moduleKey(String departmentId, String moduleCode) {
        return departmentId + "::" + moduleCode;
    }

    private String requireCode(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException(name + "只能包含字母、数字、下划线或中划线，长度 1-64");
        }
        return value;
    }

    private String requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value.trim();
    }

    private String textOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public static String now() {
        return LocalDateTime.now().toString();
    }

    public static class MetadataState {
        public Map<String, DepartmentRecord> departments = new LinkedHashMap<>();
        public Map<String, ModuleRecord> modules = new LinkedHashMap<>();
        public Map<String, DocumentRecord> documents = new LinkedHashMap<>();
        public Map<String, ChunkRecord> chunks = new LinkedHashMap<>();

        public void normalize() {
            if (departments == null) departments = new LinkedHashMap<>();
            if (modules == null) modules = new LinkedHashMap<>();
            if (documents == null) documents = new LinkedHashMap<>();
            if (chunks == null) chunks = new LinkedHashMap<>();
        }
    }

    public record DepartmentRecord(String id, String name, String description) {
        DepartmentDto toDto() {
            return new DepartmentDto(id, name, description);
        }
    }

    public record ModuleRecord(String code, String name, String departmentId, String description) {
        ModuleDto toDto() {
            return new ModuleDto(code, name, departmentId, description);
        }
    }

    public record DocumentRecord(String documentId, String fileName, String departmentId, String moduleCode,
                                 String uploadedBy, String createdAt, int chunkCount, String savedPath) {
        DocumentRecord withChunkCount(int newChunkCount) {
            return new DocumentRecord(documentId, fileName, departmentId, moduleCode, uploadedBy, createdAt,
                    newChunkCount, savedPath);
        }
    }

    public record ChunkRecord(String chunkId, String documentId, Integer chunkIndex, String content, String fileName,
                              String departmentId, String moduleCode, String status, String createdAt, String updatedAt) {
        public ChunkDto toDto() {
            return new ChunkDto(chunkId, documentId, chunkIndex, content, fileName, departmentId, moduleCode, status, updatedAt);
        }

        ChunkRecord withContent(String newContent, String newStatus, String updatedAt) {
            return new ChunkRecord(chunkId, documentId, chunkIndex, newContent, fileName, departmentId, moduleCode,
                    newStatus, createdAt, updatedAt);
        }
    }
}
