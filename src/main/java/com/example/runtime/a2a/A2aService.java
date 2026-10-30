package com.example.runtime.a2a;

import com.example.runtime.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class A2aService {
    public record Peer(@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,60}") String id,
                       @NotBlank String endpoint, @NotEmpty Set<String> readers,
                       OutboundAuth.Credential credential) {}
    private final ResourceStore store;
    private final OutboundHttp http;
    private final OutboundAuth auth;
    private final Audit audit;
    public A2aService(ResourceStore store,OutboundHttp http,OutboundAuth auth,Audit audit) {
        this.store=store;this.http=http;this.auth=auth;this.audit=audit;
    }
    public Peer register(Actor actor,Peer peer) {
        actor.require("a2a:manage");http.check(peer.endpoint());auth.validate(peer.credential());
        store.create(actor.tenant(),"a2a-peer",peer.id(),peer);
        audit.record(actor,"a2a.register",peer.id(),"SUCCESS");
        return peer;
    }
    public JsonNode invoke(Actor actor,String peerId,String text) {
        actor.require("a2a:invoke");
        var peer=store.get(actor.tenant(),"a2a-peer",peerId,Peer.class);
        if(!actor.matches(peer.readers())) throw Problem.forbidden();
        if(text==null || text.isBlank() || text.length()>16000) throw Problem.bad("Invalid A2A message");
        audit.record(actor,"a2a.invoke",peerId,"STARTED");
        String id=UUID.randomUUID().toString();
        var headers=auth.headers(actor,peer.credential());
        var response=http.json("POST",peer.endpoint(),headers,Map.of(
                "jsonrpc","2.0","id",id,"method","message/send","params",Map.of(
                        "message",Map.of("kind","message","role","user","messageId",UUID.randomUUID().toString(),
                                "parts",List.of(Map.of("kind","text","text",text))),
                        "configuration",Map.of("blocking",false))));
        if(!"2.0".equals(response.path("jsonrpc").asText()) || !id.equals(response.path("id").asText())
                || response.has("error") || !response.has("result")) throw Problem.unavailable("A2A peer returned an invalid response");
        var result=response.path("result");
        for(int attempt=0;attempt<20 && "task".equals(result.path("kind").asText())
                && !Set.of("completed","failed","canceled","rejected","auth-required","input-required")
                .contains(result.path("status").path("state").asText());attempt++) {
            try {Thread.sleep(500);}
            catch(InterruptedException e) {Thread.currentThread().interrupt();throw Problem.unavailable("Delegation interrupted");}
            result=task(actor,peerId,result.path("id").asText(),headers);
        }
        audit.record(actor,"a2a.invoke",peerId,"SUCCESS");
        return result;
    }
    public JsonNode task(Actor actor,String peerId,String taskId) {return task(actor,peerId,taskId,null);}
    private JsonNode task(Actor actor,String peerId,String taskId,Map<String,String> existingHeaders) {
        actor.require("a2a:invoke");
        var peer=store.get(actor.tenant(),"a2a-peer",peerId,Peer.class);
        if(!actor.matches(peer.readers())) throw Problem.forbidden();
        if(taskId==null || taskId.isBlank() || taskId.length()>200) throw Problem.bad("Invalid A2A task ID");
        String id=UUID.randomUUID().toString();
        var response=http.json("POST",peer.endpoint(),existingHeaders==null?auth.headers(actor,peer.credential()):existingHeaders,
                Map.of("jsonrpc","2.0","id",id,"method","tasks/get","params",Map.of("id",taskId)));
        if(!id.equals(response.path("id").asText()) || response.has("error") || !response.has("result"))
            throw Problem.unavailable("Could not read delegated A2A task");
        return response.path("result");
    }
}
