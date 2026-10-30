package com.example.runtime.knowledge;

import com.example.runtime.core.OutboundAuth;
import jakarta.validation.constraints.*;
import java.util.Set;

public final class KnowledgeModels {
    private KnowledgeModels() {}
    public enum Engine { CUSTOM, RAGFLOW }
    public record CreateKb(@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,60}") String id,
                           @NotNull Engine engine, @NotEmpty Set<String> readers,
                           String ragflowUrl, String datasetId, OutboundAuth.Credential credential) {}
    public record KnowledgeBase(String id, Engine engine, Set<String> readers,
                                String ragflowUrl, String datasetId, OutboundAuth.Credential credential,
                                String embeddingProfile) {}
    public record Ingest(@NotBlank @Pattern(regexp="[a-zA-Z0-9_.:-]{1,128}") String id,
                         @NotBlank @Size(max=500) String title,
                         @NotBlank @Size(max=1000000) String text, @Size(max=2000) String sourceUrl,
                         @NotEmpty Set<String> readers) {}
    public record Document(String id, String kbId, String title, String text, String sourceUrl,
                           Set<String> readers, String generation, String remoteId, String state,
                           String updatedAt, String sourceType, String sourceToken) {}
    public record Hit(String documentId, String chunkId, String title, String content,
                      String sourceUrl, double score, Engine engine) {}
    public record Search(@NotBlank @Size(max=4000) String query, @Min(1) @Max(30) int topK) {}
}
