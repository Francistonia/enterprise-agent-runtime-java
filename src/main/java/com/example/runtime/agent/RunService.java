package com.example.runtime.agent;

import com.example.runtime.core.*;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class RunService {
    public record Run(String id,String agentId,String sessionId,String owner,String state,String output,String error,
                      List<RuntimeToolset.Evidence> evidence,String createdAt,String updatedAt) {
        public boolean terminal() { return Set.of("COMPLETED","FAILED","CANCELLED").contains(state); }
    }
    private record Lease(String runId,long expiresAt) {}
    private record Receipt(String request,String runId) {}
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16),r -> {Thread t=new Thread(r,"agent-worker");t.setDaemon(true);return t;});
    private final ConcurrentMap<String,Future<?>> futures=new ConcurrentHashMap<>();
    private final ResourceStore store;
    private final AgentService agents;
    private final com.example.runtime.knowledge.KnowledgeService knowledge;
    private final Audit audit;
    private final long leaseSeconds;
    public RunService(ResourceStore store,AgentService agents,com.example.runtime.knowledge.KnowledgeService knowledge,
                      Audit audit,Environment env) {
        this.store=store;this.agents=agents;this.knowledge=knowledge;this.audit=audit;
        leaseSeconds=env.getProperty("runtime.run-timeout-seconds",Long.class,180L)+90;
    }
    public Run submit(Actor actor,String agentId,String sessionId,String text) {
        agents.readable(actor,agentId);
        if(text==null || text.isBlank() || text.length()>16000) throw Problem.bad("Message must contain 1 to 16000 characters");
        if(sessionId==null || sessionId.isBlank()) sessionId=UUID.randomUUID().toString();
        if(!sessionId.matches("[a-zA-Z0-9_-]{1,100}")) throw Problem.bad("Invalid session ID");
        try {
            var session=agents.session(actor,sessionId);
            if(!session.agentId().equals(agentId)) throw Problem.forbidden();
        } catch(Problem e) { if(e.status()!=org.springframework.http.HttpStatus.NOT_FOUND) throw e; }
        String id=UUID.randomUUID().toString(),leaseId=leaseKey(sessionId);
        var lease=new Lease(id,Instant.now().plusSeconds(leaseSeconds).getEpochSecond());
        try { store.create(actor.tenant(),"session-lease",leaseId,lease); }
        catch(Problem e) {
            if(e.status()!=org.springframework.http.HttpStatus.CONFLICT) throw e;
            store.modify(actor.tenant(),"session-lease",leaseId,Lease.class,old -> {
                if(old.expiresAt()>Instant.now().getEpochSecond()) throw Problem.conflict("Session already has an active run");
                return lease;
            });
        }
        String now=Instant.now().toString();
        Run run=new Run(id,agentId,sessionId,actor.subject(),"QUEUED",null,null,List.of(),now,now);
        store.create(actor.tenant(),"run",id,run);
        String session= sessionId;
        FutureTask<Void> task=new FutureTask<>(() -> { execute(actor,run,text); return null; });
        futures.put(id,task);
        try { workers.execute(task); }
        catch(RejectedExecutionException e) {
            futures.remove(id);finish(actor,run,"FAILED",null,"Worker capacity is exhausted",List.of());release(actor,session,id);
            throw Problem.unavailable("Worker capacity is exhausted");
        }
        audit.record(actor,"run.submit",id,"SUCCESS");
        return run;
    }
    public Run submitIdempotent(Actor actor,String agent,String session,String text,String key) {
        if(key==null || key.isBlank()) return submit(actor,agent,session,text);
        if(key.length()>200) throw Problem.bad("Idempotency key exceeds 200 characters");
        String id=UUID.nameUUIDFromBytes((actor.subject()+"\n"+agent+"\n"+key)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String fingerprint=java.util.HexFormat.of().formatHex(digest(
                (agent+"\n"+session+"\n"+text).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try { store.create(actor.tenant(),"run-receipt",id,new Receipt(fingerprint,null)); }
        catch(Problem e) {
            if(e.status()!=org.springframework.http.HttpStatus.CONFLICT) throw e;
            var existing=store.get(actor.tenant(),"run-receipt",id,Receipt.class);
            if(!existing.request().equals(fingerprint)) throw Problem.conflict("Idempotency key was used for a different request");
            if(existing.runId()==null) throw Problem.conflict("Request submission is pending; retry later");
            return get(actor,existing.runId());
        }
        boolean submitted=false;
        try {
            Run run=submit(actor,agent,session,text);
            submitted=true;
            store.save(actor.tenant(),"run-receipt",id,new Receipt(fingerprint,run.id()),1);
            return run;
        } catch(Exception e) {
            if(!submitted) store.delete(actor.tenant(),"run-receipt",id);
            throw e;
        }
    }
    private byte[] digest(byte[] bytes) {
        try {return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);}
        catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    private void execute(Actor actor,Run run,String text) {
        try {
            var started=store.modify(actor.tenant(),"run",run.id(),Run.class,r -> r.terminal()?r:
                    copy(r,"RUNNING",null,null,List.of()));
            if(started.terminal()) return;
            String output=agents.execute(actor,run.agentId(),run.sessionId(),text,() -> cancelled(actor,run));
            var history=agents.session(actor,run.sessionId()).turns();
            var evidence=history.isEmpty()?List.<RuntimeToolset.Evidence>of():history.get(history.size()-1).evidence();
            finish(actor,run,"COMPLETED",output,null,evidence);
        } catch(Exception e) {
            finish(actor,run,"FAILED",null,e instanceof Problem?pMessage((Problem)e):"Agent execution failed",List.of());
        } finally { futures.remove(run.id());release(actor,run.sessionId(),run.id()); }
    }
    private boolean cancelled(Actor actor,Run run) {
        if(Thread.currentThread().isInterrupted()) return true;
        var lease=store.get(actor.tenant(),"session-lease",leaseKey(run.sessionId()),Lease.class);
        return !lease.runId().equals(run.id()) || lease.expiresAt()<Instant.now().getEpochSecond()
                || store.get(actor.tenant(),"run",run.id(),Run.class).terminal();
    }
    private String pMessage(Problem e) { return e.getMessage(); }
    private void finish(Actor actor,Run run,String state,String output,String error,List<RuntimeToolset.Evidence> evidence) {
        store.modify(actor.tenant(),"run",run.id(),Run.class,r -> r.terminal()?r:copy(r,state,output,error,evidence));
    }
    public Run get(Actor actor,String id) {
        Run run=store.get(actor.tenant(),"run",id,Run.class);
        if(!run.owner().equals(actor.subject())) throw Problem.forbidden();
        agents.readable(actor,run.agentId());
        if(!run.terminal() && Instant.parse(run.createdAt()).plusSeconds(leaseSeconds).isBefore(Instant.now())) {
            finish(actor,run,"FAILED",null,"Worker lease expired; submit a new run",List.of());
            run=store.get(actor.tenant(),"run",id,Run.class);
        }
        for(var evidence:run.evidence()) {
            boolean visible;
            try { visible=knowledge.documents(actor,evidence.kbId()).stream().anyMatch(d ->
                    d.id().equals(evidence.documentId()) && d.generation().equals(evidence.generation())); }
            catch(Exception e) { visible=false; }
            if(!visible) return copy(run,run.state(),null,"Source access changed; output is no longer available",run.evidence());
        }
        return run;
    }
    public Run cancel(Actor actor,String id) {
        var run=get(actor,id);
        if(run.terminal()) return run;
        finish(actor,run,"CANCELLED",null,"Cancelled by owner",List.of());
        Future<?> future=futures.get(id);
        if(future!=null) future.cancel(true);
        if("QUEUED".equals(run.state())) {
            futures.remove(id);release(actor,run.sessionId(),id);
        }
        audit.record(actor,"run.cancel",id,"SUCCESS");
        return get(actor,id);
    }
    private Run copy(Run r,String state,String output,String error,List<RuntimeToolset.Evidence> evidence) {
        return new Run(r.id(),r.agentId(),r.sessionId(),r.owner(),state,output,error,evidence,r.createdAt(),Instant.now().toString());
    }
    private void release(Actor actor,String session,String id) {
        try {
            store.modify(actor.tenant(),"session-lease",leaseKey(session),Lease.class,
                    lease -> lease.runId().equals(id)?new Lease(id,0):lease);
        } catch(Problem ignored) { }
    }
    private String leaseKey(String session) {
        return UUID.nameUUIDFromBytes(session.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    @PreDestroy public void stop() { workers.shutdownNow(); }
}
