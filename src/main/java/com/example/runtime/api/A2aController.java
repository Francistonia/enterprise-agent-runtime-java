package com.example.runtime.api;

import com.example.runtime.agent.*;
import com.example.runtime.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** A2A 0.3 JSON-RPC binding: asynchronous text tasks, get and cancel; streaming is not advertised. */
@RestController
public class A2aController {
    private final RunService runs;
    private final AgentService agents;
    public A2aController(RunService runs,AgentService agents) {this.runs=runs;this.agents=agents;}
    @GetMapping("/agents/{agent}/.well-known/agent-card.json")
    public Object card(Authentication auth,@PathVariable String agent,jakarta.servlet.http.HttpServletRequest request) {
        var actor=Actor.from(auth);agents.readable(actor,agent);
        String scheme=auth instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken?"bearer":"basic";
        String url=request.getRequestURL().toString().replace("/.well-known/agent-card.json","/a2a");
        return Map.ofEntries(Map.entry("name",agent),Map.entry("description","Enterprise Agent Runtime agent"),
                Map.entry("url",url),Map.entry("version","0.1.0"),Map.entry("protocolVersion","0.3.0"),
                Map.entry("capabilities",Map.of("streaming",false,"pushNotifications",false)),
                Map.entry("defaultInputModes",List.of("text/plain")),Map.entry("defaultOutputModes",List.of("text/plain")),
                Map.entry("skills",List.of(Map.of("id","execute","name","Execute","description","Execute an authorized task","tags",List.of("agent")))),
                Map.entry("securitySchemes",Map.of("bearer",Map.of("type","http","scheme",scheme))),
                Map.entry("security",List.of(Map.of("bearer",List.of()))));
    }
    @PostMapping("/agents/{agent}/a2a")
    public Object rpc(Authentication auth,@PathVariable String agent,@RequestBody JsonNode request) {
        Actor actor=Actor.from(auth);actor.require("a2a:invoke");agents.readable(actor,agent);
        Object id=request.has("id")?request.get("id"):null;
        try {
            if(!"2.0".equals(request.path("jsonrpc").asText()) || id==null)
                return error(id,-32600,"Invalid JSON-RPC request");
            var params=request.path("params");
            return switch(request.path("method").asText()) {
                case "message/send" -> {
                    var message=params.path("message");
                    if(!"user".equals(message.path("role").asText()) || message.path("messageId").asText().isBlank())
                        yield error(id,-32602,"A user messageId and role are required");
                    StringJoiner text=new StringJoiner("\n");
                    for(var part:message.path("parts")) {
                        if(!"text".equals(part.path("kind").asText())) throw Problem.bad("Only text parts are supported");
                        text.add(part.path("text").asText());
                    }
                    String context=message.path("contextId").asText(null);
                    var run=runs.submitIdempotent(actor,agent,context,text.toString(),message.path("messageId").asText());
                    yield result(id,task(run));
                }
                case "tasks/get","tasks/cancel" -> {
                    var run=runs.get(actor,params.path("id").asText());
                    if(!run.agentId().equals(agent)) throw Problem.missing();
                    if("tasks/cancel".equals(request.path("method").asText())) run=runs.cancel(actor,run.id());
                    yield result(id,task(run));
                }
                default -> error(id,-32601,"Method not supported");
            };
        } catch(Problem e) {
            return error(id,e.status()==org.springframework.http.HttpStatus.NOT_FOUND?-32001:-32602,e.getMessage());
        }
    }
    private Map<String,Object> task(RunService.Run run) {
        String state=switch(run.state()) {case "COMPLETED"->"completed";case "FAILED"->"failed";case "CANCELLED"->"canceled";case "QUEUED"->"submitted";default->"working";};
        Map<String,Object> task=new LinkedHashMap<>();
        task.put("kind","task");task.put("id",run.id());task.put("contextId",run.sessionId());
        task.put("status",Map.of("state",state,"timestamp",run.updatedAt()));
        if(run.output()!=null) task.put("artifacts",List.of(Map.of("artifactId",run.id(),
                "parts",List.of(Map.of("kind","text","text",run.output())))));
        return task;
    }
    private Object result(Object id,Object result) {return Map.of("jsonrpc","2.0","id",id,"result",result);}
    private Object error(Object id,int code,String message) {
        Map<String,Object> body=new LinkedHashMap<>();body.put("jsonrpc","2.0");body.put("id",id);
        body.put("error",Map.of("code",code,"message",message));return body;
    }
}
