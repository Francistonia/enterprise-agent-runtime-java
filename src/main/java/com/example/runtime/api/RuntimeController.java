package com.example.runtime.api;

import com.example.runtime.core.*;
import com.example.runtime.agent.*;
import com.example.runtime.mcp.McpService;
import com.example.runtime.knowledge.*;
import com.example.runtime.feishu.FeishuService;
import com.example.runtime.a2a.A2aService;
import com.example.runtime.sandbox.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RuntimeController {
    public record Chat(@Size(max=100) String sessionId,@NotBlank @Size(max=16000) String message) {}
    public record Call(@NotNull Map<String,Object> arguments) {}
    public record Readers(@NotNull Set<String> readers) {}
    public record Command(@NotBlank @Size(max=16000) String command,@Min(1) @Max(30) int timeoutSeconds) {}
    private final AgentService agents;private final RunService runs;private final McpService mcp;
    private final KnowledgeService kb;private final A2aService a2a;private final FeishuService feishu;
    private final SandboxService sandbox;private final Audit audit;
    public RuntimeController(AgentService agents,RunService runs,McpService mcp,KnowledgeService kb,A2aService a2a,
                             FeishuService feishu,SandboxService sandbox,Audit audit) {
        this.agents=agents;this.runs=runs;this.mcp=mcp;this.kb=kb;this.a2a=a2a;this.feishu=feishu;this.sandbox=sandbox;this.audit=audit;
    }
    @GetMapping("/me") public Object me(Authentication a) {
        var actor=Actor.from(a);return Map.of("tenant",actor.tenant(),"subject",actor.subject(),"scopes",actor.scopes());
    }
    @GetMapping("/agents") public Object agents(Authentication a) {return agents.list(Actor.from(a));}
    @PostMapping("/agents") @ResponseStatus(HttpStatus.CREATED)
    public Object createAgent(Authentication a,@Valid @RequestBody AgentService.Definition body) {return agents.create(Actor.from(a),body);}
    @PostMapping("/agents/{id}/runs") @ResponseStatus(HttpStatus.ACCEPTED)
    public Object run(Authentication a,@PathVariable String id,@Valid @RequestBody Chat body,
                      @RequestHeader(value="Idempotency-Key",required=false) String key) {
        return runs.submitIdempotent(Actor.from(a),id,body.sessionId(),body.message(),key);
    }
    @GetMapping("/runs/{id}") public Object run(Authentication a,@PathVariable String id) {return runs.get(Actor.from(a),id);}
    @PostMapping("/runs/{id}/cancel") public Object cancel(Authentication a,@PathVariable String id) {return runs.cancel(Actor.from(a),id);}
    @GetMapping("/sessions/{id}") public Object session(Authentication a,@PathVariable String id) {return agents.session(Actor.from(a),id);}
    @GetMapping("/mcp") public Object mcp(Authentication a) {
        return mcp.catalog(Actor.from(a)).stream().map(d -> Map.of("id",d.id(),"url",d.url(),"allowedTools",d.allowedTools())).toList();
    }
    @PostMapping("/mcp") @ResponseStatus(HttpStatus.CREATED)
    public Object mcp(Authentication a,@Valid @RequestBody McpService.Definition body) {return mcp.register(Actor.from(a),body);}
    @PostMapping("/mcp/{id}/discover") public Object discover(Authentication a,@PathVariable String id) {return mcp.discover(Actor.from(a),id);}
    @PutMapping("/agents/{agent}/mcp/{connector}")
    public Object install(Authentication a,@PathVariable String agent,@PathVariable String connector) {return mcp.install(Actor.from(a),agent,connector);}
    @DeleteMapping("/agents/{agent}/mcp/{connector}")
    public Object uninstall(Authentication a,@PathVariable String agent,@PathVariable String connector) {
        mcp.uninstall(Actor.from(a),agent,connector);return Map.of("uninstalled",connector);
    }
    @PostMapping("/agents/{agent}/mcp/{connector}/tools/{tool}")
    public Object call(Authentication a,@PathVariable String agent,@PathVariable String connector,@PathVariable String tool,
                       @Valid @RequestBody Call body) {
        var actor=Actor.from(a);agents.readable(actor,agent);return mcp.call(actor,agent,connector,tool,body.arguments());
    }
    @GetMapping("/knowledge-bases") public Object bases(Authentication a) {return kb.list(Actor.from(a));}
    @PostMapping("/knowledge-bases") @ResponseStatus(HttpStatus.CREATED)
    public Object base(Authentication a,@Valid @RequestBody KnowledgeModels.CreateKb body) {return kb.create(Actor.from(a),body);}
    @GetMapping("/knowledge-bases/{id}/documents")
    public Object documents(Authentication a,@PathVariable String id) {return kb.documents(Actor.from(a),id);}
    @PostMapping("/knowledge-bases/{id}/documents") @ResponseStatus(HttpStatus.CREATED)
    public Object ingest(Authentication a,@PathVariable String id,@Valid @RequestBody KnowledgeModels.Ingest body) {return kb.ingest(Actor.from(a),id,body);}
    @PutMapping("/knowledge-bases/{id}/documents/{doc}/readers")
    public Object readers(Authentication a,@PathVariable String id,@PathVariable String doc,@Valid @RequestBody Readers body) {
        return kb.changeReaders(Actor.from(a),id,doc,body.readers());
    }
    @DeleteMapping("/knowledge-bases/{id}/documents/{doc}")
    public Object delete(Authentication a,@PathVariable String id,@PathVariable String doc) {
        kb.delete(Actor.from(a),id,doc);return Map.of("deleted",doc);
    }
    @PostMapping("/knowledge-bases/{id}/search")
    public Object search(Authentication a,@PathVariable String id,@Valid @RequestBody KnowledgeModels.Search body) {
        return kb.search(Actor.from(a),id,body.query(),body.topK());
    }
    @PostMapping("/a2a/peers") @ResponseStatus(HttpStatus.CREATED)
    public Object peer(Authentication a,@Valid @RequestBody A2aService.Peer body) {return a2a.register(Actor.from(a),body);}
    @PostMapping("/a2a/peers/{id}/invoke")
    public Object delegate(Authentication a,@PathVariable String id,@Valid @RequestBody Chat body) {return a2a.invoke(Actor.from(a),id,body.message());}
    @GetMapping("/a2a/peers/{id}/tasks/{task}")
    public Object peerTask(Authentication a,@PathVariable String id,@PathVariable String task) {return a2a.task(Actor.from(a),id,task);}
    @PostMapping("/feishu/bindings") @ResponseStatus(HttpStatus.CREATED)
    public Object bind(Authentication a,@Valid @RequestBody FeishuService.Binding body) {return feishu.bind(Actor.from(a),body);}
    @PutMapping("/feishu/users")
    public Object user(Authentication a,@RequestBody FeishuService.UserAccount body) {feishu.linkUser(Actor.from(a),body);return Map.of("linked",body.subject());}
    @PostMapping("/feishu/bindings/{id}/sync")
    public Object sync(Authentication a,@PathVariable String id) {return feishu.sync(Actor.from(a),id);}
    @PostMapping("/sandbox/execute")
    public Object execute(Authentication a,@Valid @RequestBody Command body) {return sandbox.execute(Actor.from(a),body.command(),body.timeoutSeconds());}
    @GetMapping("/audit") public Object audit(Authentication a) {return audit.list(Actor.from(a));}
}
