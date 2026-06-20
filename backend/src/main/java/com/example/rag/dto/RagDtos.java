package com.example.rag.dto;

import java.util.List;

public class RagDtos {
    public record UploadResult(String documentId, String fileName, int chunkCount) {}
    public record ManualDocumentRequest(String fileName, String departmentId, String moduleCode,
                                        String uploadedBy, List<String> chunks) {}
    public record SearchRequest(String departmentId, String moduleCode, String question, Integer topK) {}
    public record ChatRequest(String departmentId, String moduleCode, String sessionId, String question) {}
    public record SearchHit(String content, String documentId, String fileName,
                            String departmentId, String moduleCode, Integer chunkIndex, Double score) {}
    public record DepartmentDto(String id, String name, String description) {}
    public record ModuleDto(String code, String name, String departmentId, String description) {}
    public record UpsertDepartmentRequest(String id, String name, String description) {}
    public record UpsertModuleRequest(String code, String name, String departmentId, String description) {}
    public record DocumentSummary(String documentId, String fileName, String departmentId, String moduleCode,
                                  String uploadedBy, String createdAt, int chunkCount) {}
    public record ChunkDto(String chunkId, String documentId, Integer chunkIndex, String content, String fileName,
                           String departmentId, String moduleCode, String status, String updatedAt) {}
    public record AddChunkRequest(String content) {}
    public record UpdateChunkRequest(String content, String status) {}
}
