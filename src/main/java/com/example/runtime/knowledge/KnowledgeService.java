package com.example.runtime.knowledge;

import com.example.runtime.core.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.runtime.knowledge.KnowledgeModels.*;

@Service
public class KnowledgeService {
    private final ResourceStore store;
    private final Chunker chunker;
    private final EmbeddingService embedding;
    private final OceanbaseVectorStore vectors;
    private final RagflowEngine ragflow;
    private final OutboundHttp http;
    private final OutboundAuth auth;
    private final Audit audit;
    private final com.example.runtime.feishu.FeishuPermissionGate feishu;
    public KnowledgeService(ResourceStore store, Chunker chunker, EmbeddingService embedding,
            OceanbaseVectorStore vectors, RagflowEngine ragflow, OutboundHttp http, OutboundAuth auth, Audit audit,
            com.example.runtime.feishu.FeishuPermissionGate feishu) {
        this.store=store; this.chunker=chunker; this.embedding=embedding; this.vectors=vectors;
        this.ragflow=ragflow; this.http=http; this.auth=auth; this.audit=audit;this.feishu=feishu;
    }
    public KnowledgeBase create(Actor actor, CreateKb input) {
        actor.require("kb:manage");
        if (input.engine()==Engine.RAGFLOW) {
            http.check(input.ragflowUrl()); auth.validate(input.credential());
            if (input.datasetId()==null || !input.datasetId().matches("[a-zA-Z0-9_-]{1,128}"))
                throw Problem.bad("RAGFlow datasetId is required");
        }
        var kb = new KnowledgeBase(input.id(),input.engine(),Set.copyOf(input.readers()),input.ragflowUrl(),
                input.datasetId(),input.credential(),input.engine()==Engine.CUSTOM?embedding.profile():"ragflow-managed");
        store.create(actor.tenant(),"kb",kb.id(),kb);
        audit.record(actor,"kb.create",kb.id(),"SUCCESS");
        return kb;
    }
    public KnowledgeBase readable(Actor actor,String id) {
        actor.require("kb:read");
        var kb=store.get(actor.tenant(),"kb",id,KnowledgeBase.class);
        if (!actor.matches(kb.readers())) throw Problem.forbidden();
        return kb;
    }
    public List<KnowledgeBase> list(Actor actor) {
        actor.require("kb:read");
        return store.list(actor.tenant(),"kb",KnowledgeBase.class).stream().filter(k -> actor.matches(k.readers())).toList();
    }
    public List<Document> documents(Actor actor,String kbId) {
        readable(actor,kbId);
        return store.list(actor.tenant(),"document",Document.class).stream()
                .filter(d -> d.kbId().equals(kbId) && !"DELETED".equals(d.state()) && actor.matches(d.readers()))
                .filter(d -> feishu.visible(actor,d)).toList();
    }
    @Transactional
    public Document ingest(Actor actor,String kbId,Ingest input) {
        return ingestSource(actor,kbId,input,"UPLOAD",null);
    }
    @Transactional
    public Document ingestSource(Actor actor,String kbId,Ingest input,String sourceType,String sourceToken) {
        actor.require("kb:manage");
        var kb=store.get(actor.tenant(),"kb",kbId,KnowledgeBase.class);
        var old=findDocument(actor,kbId,input.id());
        String generation=UUID.randomUUID().toString();
        String remoteId=null;
        if (kb.engine()==Engine.CUSTOM) {
            ensureProfile(kb);
            var chunks=chunker.split(input.text());
            List<double[]> embedded=new ArrayList<>();
            for(int i=0;i<chunks.size();i+=16)
                embedded.addAll(embedding.embed(chunks.subList(i,Math.min(i+16,chunks.size()))));
            vectors.stage(actor.tenant(),kbId,input.id(),generation,chunks,embedded);
        } else remoteId=ragflow.ingest(actor,kb,input);
        var doc=new Document(input.id(),kbId,input.title(),input.text(),input.sourceUrl(),Set.copyOf(input.readers()),
                generation,remoteId,kb.engine()==Engine.CUSTOM?"READY":"INDEXING",Instant.now().toString(),sourceType,sourceToken);
        String key=key(kbId,input.id());
        if(old==null) store.create(actor.tenant(),"document",key,doc);
        else store.save(actor.tenant(),"document",key,doc,old.revision());
        if(kb.engine()==Engine.CUSTOM) vectors.prune(actor.tenant(),kbId,input.id(),generation);
        // Old RAGFlow documents are no longer eligible: the active remote-ID mapping is authoritative.
        audit.record(actor,"kb.ingest",kbId+"/"+input.id(),"SUCCESS");
        return doc;
    }
    public List<Hit> search(Actor actor,String kbId,String query,int topK) {
        if(query==null || query.isBlank() || query.length()>4000 || topK<1 || topK>30) throw Problem.bad("Invalid search");
        var kb=readable(actor,kbId);
        Map<String,Document> allowed=new LinkedHashMap<>();
        documents(actor,kbId).forEach(d -> allowed.put(d.id(),d));
        if(allowed.isEmpty()) return List.of();
        List<Hit> found;
        if(kb.engine()==Engine.CUSTOM) {
            ensureProfile(kb);
            found=vectors.search(actor.tenant(),kb,allowed,embedding.embed(List.of(query)).get(0),topK);
        } else found=ragflow.search(actor,kb,allowed,query,topK);
        // Re-read authoritative ACLs after remote retrieval, before anything reaches the model.
        var current=new HashSet<String>();
        documents(actor,kbId).forEach(d -> {
            var original=allowed.get(d.id());
            if(original!=null && original.generation().equals(d.generation())) current.add(d.id());
        });
        audit.record(actor,"kb.search",kbId,"SUCCESS");
        return found.stream().filter(h -> current.contains(h.documentId())).toList();
    }
    public Document changeReaders(Actor actor,String kb,String docId,Set<String> readers) {
        actor.require("kb:manage");
        if(readers==null) throw Problem.bad("readers is required");
        var result=store.modify(actor.tenant(),"document",key(kb,docId),Document.class,
                d -> new Document(d.id(),d.kbId(),d.title(),d.text(),d.sourceUrl(),Set.copyOf(readers),
                        d.generation(),d.remoteId(),d.state(),Instant.now().toString(),d.sourceType(),d.sourceToken()));
        audit.record(actor,"kb.acl",kb+"/"+docId,"SUCCESS");
        return result;
    }
    public void delete(Actor actor,String kb,String docId) {
        actor.require("kb:manage");
        var doc=store.modify(actor.tenant(),"document",key(kb,docId),Document.class,
                d -> new Document(d.id(),d.kbId(),d.title(),"",d.sourceUrl(),Set.of(),d.generation(),
                        d.remoteId(),"DELETED",Instant.now().toString(),d.sourceType(),d.sourceToken()));
        // Local revocation commits first, even if the upstream cleanup fails.
        audit.record(actor,"kb.delete",kb+"/"+docId,"REVOKED");
        var base=store.get(actor.tenant(),"kb",kb,KnowledgeBase.class);
        if(base.engine()==Engine.RAGFLOW) ragflow.delete(actor,base,doc.remoteId());
        else vectors.removeGeneration(actor.tenant(),kb,docId,doc.generation());
    }
    private void ensureProfile(KnowledgeBase kb) {
        if(!kb.embeddingProfile().equals(embedding.profile())) throw Problem.conflict("Embedding profile changed; create and rebuild a new knowledge base");
    }
    private ResourceStore.Versioned<Document> findDocument(Actor actor,String kb,String id) {
        try{return store.versioned(actor.tenant(),"document",key(kb,id),Document.class);}
        catch(Problem e){if(e.status()==org.springframework.http.HttpStatus.NOT_FOUND)return null;throw e;}
    }
    private String key(String kb,String id) {
        return UUID.nameUUIDFromBytes((kb+"\n"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}
