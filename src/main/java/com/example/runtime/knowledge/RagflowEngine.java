package com.example.runtime.knowledge;

import com.example.runtime.core.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.example.runtime.knowledge.KnowledgeModels.*;

@Service
public class RagflowEngine {
    private final OutboundHttp http;
    private final OutboundAuth auth;
    public RagflowEngine(OutboundHttp http, OutboundAuth auth) { this.http=http; this.auth=auth; }
    public String ingest(Actor actor, KnowledgeBase kb, Ingest input) {
        String boundary = "runtime-" + UUID.randomUUID();
        String data = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + UUID.randomUUID() + ".md\"\r\nContent-Type: text/markdown; charset=UTF-8\r\n\r\n"
                + "# " + input.title() + "\n\n" + input.text() + "\r\n--" + boundary + "--\r\n";
        var uploaded = http.parse(http.bytes("POST", base(kb) + "/api/v1/datasets/" + kb.datasetId() + "/documents",
                auth.headers(actor,kb.credential()), data.getBytes(StandardCharsets.UTF_8),
                "multipart/form-data; boundary=" + boundary, 1024*1024));
        check(uploaded);
        String remoteId = uploaded.path("data").path(0).path("id").asText();
        if (!remoteId.matches("[a-zA-Z0-9_-]{1,128}")) throw Problem.unavailable("RAGFlow returned an invalid document ID");
        check(http.json("POST", base(kb) + "/api/v1/datasets/" + kb.datasetId() + "/chunks",
                auth.headers(actor,kb.credential()), Map.of("document_ids", List.of(remoteId))));
        return remoteId;
    }
    public List<Hit> search(Actor actor, KnowledgeBase kb, Map<String, Document> allowed, String query, int topK) {
        var byRemote = new HashMap<String, Document>();
        allowed.values().stream().filter(d -> d.remoteId()!=null).forEach(d -> byRemote.put(d.remoteId(),d));
        // An empty document_ids value can mean unrestricted retrieval upstream.
        if (byRemote.isEmpty()) return List.of();
        var result = http.json("POST", base(kb)+"/api/v1/retrieval", auth.headers(actor,kb.credential()),
                Map.of("question",query, "dataset_ids",List.of(kb.datasetId()),
                        "document_ids", byRemote.keySet(), "page",1, "page_size",topK, "highlight",false));
        check(result);
        List<Hit> hits = new ArrayList<>();
        for (var chunk : result.path("data").path("chunks")) {
            var doc = byRemote.get(chunk.path("document_id").asText());
            if (doc != null) hits.add(new Hit(doc.id(),chunk.path("id").asText(),doc.title(),
                    chunk.path("content").asText(),doc.sourceUrl(),chunk.path("similarity").asDouble(),Engine.RAGFLOW));
        }
        return hits.stream().limit(topK).toList();
    }
    public void delete(Actor actor, KnowledgeBase kb, String remoteId) {
        if (remoteId != null) check(http.json("DELETE", base(kb)+"/api/v1/datasets/"+kb.datasetId()+"/documents",
                auth.headers(actor,kb.credential()),Map.of("ids",List.of(remoteId))));
    }
    private String base(KnowledgeBase kb) { return kb.ragflowUrl().replaceAll("/+$",""); }
    private void check(com.fasterxml.jackson.databind.JsonNode result) {
        if (!result.has("code") || result.path("code").asInt(-1)!=0)
            throw Problem.unavailable("RAGFlow operation failed");
    }
}
