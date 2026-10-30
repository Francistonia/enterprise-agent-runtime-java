package com.example.runtime.agent;

import com.example.runtime.core.*;
import com.example.runtime.mcp.McpService;
import com.example.runtime.knowledge.KnowledgeService;
import com.example.runtime.knowledge.KnowledgeModels;
import com.example.runtime.a2a.A2aService;
import com.example.runtime.sandbox.SandboxService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AgentService {
    public record Definition(@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,60}") String id,
                             @NotBlank @Size(max=16000) String systemPrompt, @NotEmpty Set<String> readers,
                             @NotNull Set<String> knowledgeBases,@NotNull Set<String> peers,
                             boolean sandbox,@Min(1) @Max(30) int maxIterations) {}
    public record Turn(String user,String assistant,List<RuntimeToolset.Evidence> evidence,String at) {}
    public record Session(String id,String agentId,String owner,List<Turn> turns) {}
    private final ResourceStore store;
    private final McpService mcp;
    private final KnowledgeService knowledge;
    private final A2aService a2a;
    private final SandboxService sandbox;
    private final Json json;
    private final Audit audit;
    private final HarnessEngine engine;
    public AgentService(ResourceStore store,McpService mcp,KnowledgeService knowledge,A2aService a2a,
                        SandboxService sandbox,Json json,Audit audit,HarnessEngine engine) {
        this.store=store;this.mcp=mcp;this.knowledge=knowledge;this.a2a=a2a;this.sandbox=sandbox;
        this.json=json;this.audit=audit;this.engine=engine;
    }
    public Definition create(Actor actor,Definition definition) {
        actor.require("agent:manage");
        for(String id:definition.knowledgeBases()) store.get(actor.tenant(),"kb",id,KnowledgeModels.KnowledgeBase.class);
        for(String id:definition.peers()) store.get(actor.tenant(),"a2a-peer",id,A2aService.Peer.class);
        store.create(actor.tenant(),"agent",definition.id(),definition);
        audit.record(actor,"agent.create",definition.id(),"SUCCESS");return definition;
    }
    public Definition readable(Actor actor,String id) {
        actor.require("agent:run");
        var def=store.get(actor.tenant(),"agent",id,Definition.class);
        if(!actor.matches(def.readers())) throw Problem.forbidden();
        return def;
    }
    public List<Definition> list(Actor actor) {
        actor.require("agent:run");
        return store.list(actor.tenant(),"agent",Definition.class).stream().filter(d -> actor.matches(d.readers())).toList();
    }
    public Session session(Actor actor,String id) {
        var session=store.get(actor.tenant(),"session",id,Session.class);
        if(!session.owner().equals(actor.subject())) throw Problem.forbidden();
        readable(actor,session.agentId());
        return new Session(session.id(),session.agentId(),session.owner(),
                session.turns().stream().filter(t -> evidenceVisible(actor,t)).toList());
    }
    public String execute(Actor actor,String agentId,String sessionId,String text,java.util.function.BooleanSupplier cancelled) {
        var def=readable(actor,agentId);
        ResourceStore.Versioned<Session> old;
        try { old=store.versioned(actor.tenant(),"session",sessionId,Session.class); }
        catch(Problem e) {
            if(e.status()!=org.springframework.http.HttpStatus.NOT_FOUND) throw e;
            store.create(actor.tenant(),"session",sessionId,new Session(sessionId,agentId,actor.subject(),List.of()));
            old=store.versioned(actor.tenant(),"session",sessionId,Session.class);
        }
        if(!old.value().owner().equals(actor.subject()) || !old.value().agentId().equals(agentId)) throw Problem.forbidden();
        var turns=old.value().turns().stream().filter(t -> evidenceVisible(actor,t)).toList();
        List<Msg> input=new ArrayList<>();
        for(var turn:turns.subList(Math.max(0,turns.size()-10),turns.size())) {
            input.add(new UserMessage(turn.user()));input.add(new AssistantMessage(turn.assistant()));
        }
        input.add(new UserMessage(text));
        var tools=new RuntimeToolset(actor,def,mcp,knowledge,a2a,sandbox,json,cancelled);
        var toolkit=tools.build();
        String output;
        audit.record(actor,"agent.execute",agentId,"STARTED");
        try {
            output=engine.execute(def,toolkit,input,RuntimeContext.builder()
                    .userId(actor.tenant()+":"+actor.subject()).sessionId(sessionId).put(Actor.class,actor).build());
        } finally { toolkit.closeMcpClients(); }
        if(cancelled.getAsBoolean()) throw Problem.conflict("Run was cancelled");
        List<Turn> updated=new ArrayList<>(turns);
        updated.add(new Turn(text,output,tools.evidence(),Instant.now().toString()));
        if(updated.size()>100) updated=new ArrayList<>(updated.subList(updated.size()-100,updated.size()));
        store.save(actor.tenant(),"session",sessionId,new Session(sessionId,agentId,actor.subject(),List.copyOf(updated)),old.revision());
        audit.record(actor,"agent.execute",agentId,"SUCCESS");
        return output;
    }
    private boolean evidenceVisible(Actor actor,Turn turn) {
        for(var evidence:turn.evidence()) {
            try {
                if(knowledge.documents(actor,evidence.kbId()).stream().noneMatch(d -> d.id().equals(evidence.documentId())
                        && d.generation().equals(evidence.generation()))) return false;
            } catch(Exception e) { return false; }
        }
        return true;
    }
}
