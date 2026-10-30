package com.example.runtime.knowledge;

import com.example.runtime.core.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.example.runtime.knowledge.KnowledgeModels.*;

@Repository
public class OceanbaseVectorStore {
    private final JdbcTemplate jdbc;
    private final Json json;
    private final boolean nativeVector;
    public OceanbaseVectorStore(JdbcTemplate jdbc, Json json, Environment env) {
        this.jdbc = jdbc; this.json = json;
        nativeVector = "oceanbase".equals(env.getProperty("runtime.vector.mode"));
    }
    public void stage(String tenant, String kb, String document, String generation, List<String> chunks, List<double[]> vectors) {
        for (int i = 0; i < chunks.size(); i++)
            jdbc.update("INSERT INTO runtime_chunk VALUES (?,?,?,?,?,?,?)", tenant, kb, document,
                    generation + "-" + i, generation, chunks.get(i), json.write(vectors.get(i)));
    }
    public void prune(String tenant, String kb, String document, String keepGeneration) {
        jdbc.update("DELETE FROM runtime_chunk WHERE tenant_id=? AND kb_id=? AND document_id=? AND generation_id<>?",
                tenant, kb, document, keepGeneration);
    }
    public void removeGeneration(String tenant,String kb,String document,String generation) {
        jdbc.update("DELETE FROM runtime_chunk WHERE tenant_id=? AND kb_id=? AND document_id=? AND generation_id=?",
                tenant,kb,document,generation);
    }
    public List<Hit> search(String tenant, KnowledgeBase kb, Map<String, Document> allowed, double[] query, int topK) {
        if (allowed.isEmpty()) return List.of();
        if (allowed.size() > 1000) throw Problem.unavailable("Document ACL filter exceeds this deployment's query limit");
        List<Object> args = new ArrayList<>();
        String score = nativeVector ? "1-cosine_distance(embedding,?)" : "embedding";
        if (nativeVector) args.add(json.write(query));
        args.add(tenant); args.add(kb.id());
        List<String> clauses = new ArrayList<>();
        for (var doc : allowed.values()) {
            clauses.add("(document_id=? AND generation_id=?)");
            args.add(doc.id()); args.add(doc.generation());
        }
        String sql = "SELECT document_id,chunk_id,content," + score + " AS similarity FROM runtime_chunk WHERE tenant_id=? AND kb_id=? AND ("
                + String.join(" OR ", clauses) + ")";
        if (nativeVector) { sql += " ORDER BY similarity DESC LIMIT ?"; args.add(topK); }
        var hits = jdbc.query(sql, (rs, row) -> {
            var doc = allowed.get(rs.getString(1));
            double similarity = nativeVector ? rs.getDouble(4) : cosine(query, json.read(rs.getString(4), double[].class));
            return new Hit(doc.id(), rs.getString(2), doc.title(), rs.getString(3), doc.sourceUrl(), similarity, Engine.CUSTOM);
        }, args.toArray());
        return hits.stream().sorted(Comparator.comparingDouble(Hit::score).reversed()).limit(topK).toList();
    }
    private double cosine(double[] a, double[] b) {
        if (a.length != b.length) throw Problem.conflict("Embedding profile changed; rebuild the knowledge base");
        double dot=0, na=0, nb=0;
        for (int i=0; i<a.length; i++) { dot+=a[i]*b[i]; na+=a[i]*a[i]; nb+=b[i]*b[i]; }
        return na==0 || nb==0 ? 0 : dot/Math.sqrt(na*nb);
    }
}
