package com.example.runtime.feishu;

import com.example.runtime.core.*;
import com.example.runtime.knowledge.KnowledgeService;
import static com.example.runtime.knowledge.KnowledgeModels.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class FeishuService {
    public record Binding(@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,40}") String id,
                          @NotBlank String kbId, @NotBlank @Pattern(regexp="[0-9]+") String spaceId,
                          String rootNode, @NotBlank String appId, @NotBlank String appSecretRef,
                          @NotEmpty Set<String> readers) {}
    public record UserAccount(String subject, String tokenRef) {}
    public record SyncResult(int indexed, int unchanged, int deleted, List<String> skipped) {}
    private static final String BASE="https://open.feishu.cn/open-apis";
    private final ResourceStore store;
    private final KnowledgeService knowledge;
    private final OutboundHttp http;
    private final Secrets secrets;
    private final Audit audit;
    public FeishuService(ResourceStore store,KnowledgeService knowledge,OutboundHttp http,Secrets secrets,Audit audit) {
        this.store=store;this.knowledge=knowledge;this.http=http;this.secrets=secrets;this.audit=audit;
    }
    public Binding bind(Actor actor,Binding binding) {
        actor.require("kb:manage");
        store.get(actor.tenant(),"kb",binding.kbId(),KnowledgeBase.class);
        if(binding.rootNode()!=null && !binding.rootNode().isBlank() && !binding.rootNode().matches("[a-zA-Z0-9]+"))
            throw Problem.bad("Invalid Wiki root node token");
        store.create(actor.tenant(),"feishu-binding",binding.id(),binding);
        audit.record(actor,"feishu.bind",binding.id(),"SUCCESS");
        return binding;
    }
    public void linkUser(Actor actor,UserAccount account) {
        actor.require("kb:manage");
        if(account.subject()==null || account.subject().isBlank() || account.tokenRef()==null
                || !account.tokenRef().matches("RUNTIME_SECRET_[A-Z0-9_]{1,100}")) throw Problem.bad("Invalid user token reference");
        String id=userKey(account.subject());
        try {
            var old=store.versioned(actor.tenant(),"feishu-user",id,UserAccount.class);
            store.save(actor.tenant(),"feishu-user",id,account,old.revision());
        } catch(Problem e) {
            if(e.status()!=org.springframework.http.HttpStatus.NOT_FOUND) throw e;
            store.create(actor.tenant(),"feishu-user",id,account);
        }
        audit.record(actor,"feishu.link-user",account.subject(),"SUCCESS");
    }
    public boolean canRead(Actor actor,Document doc) {
        if(!doc.sourceType().startsWith("FEISHU:")) return true;
        try {
            var account=store.get(actor.tenant(),"feishu-user",userKey(actor.subject()),UserAccount.class);
            String token=secrets.get(account.tokenRef());
            String[] source=doc.sourceToken().split(":",2);
            if(source.length!=2) return false;
            if("docx".equals(source[0])) {
                api("GET",BASE+"/docx/v1/documents/"+safe(source[1])+"/raw_content",token,null);
            } else if("file".equals(source[0])) {
                http.bytes("GET",BASE+"/drive/v1/files/"+safe(source[1])+"/download",
                        Map.of("Authorization","Bearer "+token),null,null,2*1024*1024);
            } else return false;
            return true;
        } catch(Exception e) { return false; } // Missing, expired, denied, and unavailable all fail closed.
    }
    public SyncResult sync(Actor actor,String bindingId) {
        actor.require("kb:manage");
        var b=store.get(actor.tenant(),"feishu-binding",bindingId,Binding.class);
        var auth=http.json("POST",BASE+"/auth/v3/tenant_access_token/internal",Map.of(),
                Map.of("app_id",b.appId(),"app_secret",secrets.get(b.appSecretRef())));
        if(auth.path("code").asInt(-1)!=0) throw Problem.unavailable("Feishu application authentication failed");
        String token=auth.path("tenant_access_token").asText();
        if(token.isBlank()) throw Problem.unavailable("Feishu access token is missing");
        List<JsonNode> nodes=new ArrayList<>();
        var parents=new ArrayDeque<String>();
        Set<String> visited=new HashSet<>();
        if(b.rootNode()!=null && !b.rootNode().isBlank()) {
            var root=api("GET",BASE+"/wiki/v2/spaces/get_node?token="+safe(b.rootNode()),token,null).path("data").path("node");
            if(!b.spaceId().equals(root.path("space_id").asText())) throw Problem.bad("Wiki root belongs to a different space");
            nodes.add(root); parents.add(b.rootNode());
        } else parents.add("");
        while(!parents.isEmpty()) {
            String parent=parents.remove();
            if(!visited.add(parent)) continue;
            String page="";
            do {
                String url=BASE+"/wiki/v2/spaces/"+b.spaceId()+"/nodes?page_size=50"
                        +(parent.isEmpty()?"":"&parent_node_token="+safe(parent))
                        +(page.isEmpty()?"":"&page_token="+encode(page));
                var data=api("GET",url,token,null).path("data");
                for(var node:data.path("items")) {
                    nodes.add(node);
                    if(node.path("has_child").asBoolean()) parents.add(node.path("node_token").asText());
                    if(nodes.size()>1000) throw Problem.bad("Wiki sync exceeds 1000 nodes; bind a smaller subtree");
                }
                String next=data.path("page_token").asText();
                if(data.path("has_more").asBoolean() && (next.isBlank() || next.equals(page)))
                    throw Problem.unavailable("Feishu returned an invalid pagination cursor");
                page=data.path("has_more").asBoolean()?next:"";
            } while(!page.isEmpty());
        }
        int indexed=0,unchanged=0,deleted=0;
        List<String> skipped=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        var previous=store.list(actor.tenant(),"document",Document.class).stream()
                .filter(d -> d.kbId().equals(b.kbId()) && d.sourceType().equals("FEISHU:"+b.id())).toList();
        for(var node:nodes) {
            String type=node.path("obj_type").asText(),obj=node.path("obj_token").asText(),title=node.path("title").asText();
            String nodeToken=node.path("node_token").asText();
            String id=UUID.nameUUIDFromBytes((b.id()+"/"+nodeToken).getBytes(StandardCharsets.UTF_8)).toString();
            String text;
            if("docx".equals(type))
                text=api("GET",BASE+"/docx/v1/documents/"+safe(obj)+"/raw_content",token,null).path("data").path("content").asText();
            else if("file".equals(type) && title.toLowerCase(Locale.ROOT).matches(".*\\.(txt|md|csv|json)$"))
                text=new String(http.bytes("GET",BASE+"/drive/v1/files/"+safe(obj)+"/download",
                        Map.of("Authorization","Bearer "+token),null,null,2*1024*1024),StandardCharsets.UTF_8);
            else { skipped.add(nodeToken+":"+type); continue; }
            seen.add(id);
            if(text.isBlank()) { skipped.add(nodeToken+":empty"); continue; }
            if(text.length()>1000000) throw Problem.bad("Wiki document exceeds text ingestion limit");
            var old=previous.stream().filter(d -> d.id().equals(id)).findFirst().orElse(null);
            if(old!=null && old.text().equals(text) && old.title().equals(title)
                    && old.readers().equals(b.readers()) && !"DELETED".equals(old.state())) { unchanged++;continue; }
            knowledge.ingestSource(actor,b.kbId(),new Ingest(id,title,text,
                    "https://www.feishu.cn/wiki/"+safe(nodeToken),b.readers()),"FEISHU:"+b.id(),type+":"+safe(obj));
            indexed++;
        }
        // Only reconcile deletions after a completely successful traversal and content fetch.
        for(var old:previous) if(!seen.contains(old.id()) && !"DELETED".equals(old.state())) {
            knowledge.delete(actor,b.kbId(),old.id());deleted++;
        }
        audit.record(actor,"feishu.sync",b.id(),"SUCCESS");
        return new SyncResult(indexed,unchanged,deleted,List.copyOf(skipped));
    }
    private JsonNode api(String method,String url,String token,Object body) {
        var response=http.json(method,url,Map.of("Authorization","Bearer "+token),body);
        if(response.path("code").asInt(-1)!=0) throw Problem.unavailable("Feishu API operation failed");
        return response;
    }
    private String safe(String token) {
        if(token==null || !token.matches("[a-zA-Z0-9_-]{1,256}")) throw Problem.bad("Invalid Feishu token");
        return token;
    }
    private String userKey(String subject) { return UUID.nameUUIDFromBytes(subject.getBytes(StandardCharsets.UTF_8)).toString(); }
    private String encode(String value) { return java.net.URLEncoder.encode(value,StandardCharsets.UTF_8); }
}
