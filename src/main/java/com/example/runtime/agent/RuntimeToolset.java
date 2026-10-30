package com.example.runtime.agent;

import com.example.runtime.core.*;
import com.example.runtime.mcp.McpService;
import com.example.runtime.knowledge.KnowledgeService;
import com.example.runtime.a2a.A2aService;
import com.example.runtime.sandbox.SandboxService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class RuntimeToolset {
    public record Evidence(String kbId,String documentId,String generation) {}
    private final Actor actor;
    private final AgentService.Definition agent;
    private final McpService mcp;
    private final KnowledgeService knowledge;
    private final A2aService a2a;
    private final SandboxService sandbox;
    private final Json json;
    private final List<Evidence> evidence=new CopyOnWriteArrayList<>();
    private final java.util.function.BooleanSupplier cancelled;
    public RuntimeToolset(Actor actor,AgentService.Definition agent,McpService mcp,KnowledgeService knowledge,
                          A2aService a2a,SandboxService sandbox,Json json,java.util.function.BooleanSupplier cancelled) {
        this.actor=actor;this.agent=agent;this.mcp=mcp;this.knowledge=knowledge;this.a2a=a2a;this.sandbox=sandbox;this.json=json;
        this.cancelled=cancelled;
    }
    public List<Evidence> evidence() { return List.copyOf(evidence); }
    public Toolkit build() {
        Toolkit toolkit=new Toolkit();toolkit.registerTool(this);
        if(actor.can("mcp:invoke")) for(var binding:mcp.bindings(actor,agent.id())) {
            var definition=mcp.definition(actor,binding.mcpId());
            if(!actor.matches(definition.readers())) continue;
            for(var tool:binding.tools()) toolkit.registerAgentTool(new McpProxy(binding.mcpId(),tool));
        }
        return toolkit;
    }
    @Tool(name="list_mcp_catalog",description="List approved MCP connectors available in this tenant",readOnly=true)
    public ToolResultBlock catalog() {
        return result(() -> mcp.catalog(actor).stream().map(d -> Map.of("id",d.id(),"allowedTools",d.allowedTools())).toList());
    }
    @Tool(name="install_mcp",description="Attach an approved MCP connector to this agent. Requires mcp:manage. Tools are available next turn.",concurrencySafe=false)
    public ToolResultBlock install(@ToolParam(name="mcpId",description="Approved connector ID") String id) {
        return result(() -> mcp.install(actor,agent.id(),id));
    }
    @Tool(name="uninstall_mcp",description="Detach an MCP connector and revoke subsequent invocations. Requires mcp:manage.",concurrencySafe=false)
    public ToolResultBlock uninstall(@ToolParam(name="mcpId",description="Connector ID") String id) {
        return result(() -> {mcp.uninstall(actor,agent.id(),id);return Map.of("uninstalled",id);});
    }
    @Tool(name="search_knowledge",description="Search an authorized knowledge base and return source citations",readOnly=true)
    public ToolResultBlock search(@ToolParam(name="kbId",description="Knowledge base ID") String id,
                                 @ToolParam(name="query",description="Search query") String query) {
        return result(() -> {
            if(!agent.knowledgeBases().contains(id)) throw Problem.forbidden();
            var hits=knowledge.search(actor,id,query,6);
            var docs=knowledge.documents(actor,id);
            for(var doc:docs) if(hits.stream().anyMatch(h -> h.documentId().equals(doc.id())))
                evidence.add(new Evidence(id,doc.id(),doc.generation()));
            return hits;
        });
    }
    @Tool(name="delegate_agent",description="Send a text task to an approved remote A2A agent")
    public ToolResultBlock delegate(@ToolParam(name="peerId",description="Approved peer ID") String id,
                                   @ToolParam(name="message",description="Task instructions") String message) {
        return result(() -> {
            if(!agent.peers().contains(id)) throw Problem.forbidden();
            return a2a.invoke(actor,id,message);
        });
    }
    @Tool(name="execute_command",description="Execute a command inside an isolated Docker sandbox, without network access")
    public ToolResultBlock execute(@ToolParam(name="command",description="POSIX shell command") String command) {
        return result(() -> {
            if(!agent.sandbox()) throw Problem.forbidden();
            var executed=sandbox.execute(actor,command,30);
            if(executed.exitCode()!=0) return Map.of("failed",true,"result",executed);
            return executed;
        });
    }
    @Tool(name="get_delegated_task",description="Read the status and artifacts of a previously delegated A2A task",readOnly=true)
    public ToolResultBlock delegatedTask(@ToolParam(name="peerId",description="Approved peer ID") String peer,
                                        @ToolParam(name="taskId",description="Remote task ID") String task) {
        return result(() -> {
            if(!agent.peers().contains(peer)) throw Problem.forbidden();
            return a2a.task(actor,peer,task);
        });
    }
    private ToolResultBlock result(Supplier<Object> operation) {
        try {
            if(cancelled.getAsBoolean()) throw Problem.conflict("Run was cancelled");
            return ToolResultBlock.text(json.write(operation.get())).withState(io.agentscope.core.message.ToolResultState.SUCCESS);
        }
        catch(Problem e) { return ToolResultBlock.error(e.getMessage()); }
        catch(Exception e) { return ToolResultBlock.error("Tool operation failed"); }
    }
    private class McpProxy extends ToolBase {
        private final String connector;
        private final String remote;
        McpProxy(String connector,McpService.ToolDescription tool) {
            super(ToolBase.builder().name("mcp__"+connector+"__"+tool.name())
                    .description(tool.description()==null?tool.name():tool.description())
                    .inputSchema(tool.schema()).readOnly(false).concurrencySafe(false));
            this.connector=connector;this.remote=tool.name();
        }
        @Override public Mono<PermissionDecision> checkPermissions(Map<String,Object> input,PermissionContextState context) {
            // The service enforces the tenant, principal, binding and tool allowlist again at dispatch.
            return Mono.just(actor.can("mcp:invoke")?PermissionDecision.allow("Server policy applies"):
                    PermissionDecision.deny("Missing MCP permission"));
        }
        @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.fromCallable(() -> {
                try {
                    if(cancelled.getAsBoolean()) return ToolResultBlock.error("Run was cancelled");
                    var response=mcp.call(actor,agent.id(),connector,remote,param.getInput());
                    if(response==null) return ToolResultBlock.error("MCP returned no result");
                    String value=json.write(response);
                    if(value.length()>65536) return ToolResultBlock.error("MCP result exceeds context limit");
                    return Boolean.TRUE.equals(response.isError())?ToolResultBlock.error(value):
                            ToolResultBlock.text(value).withState(io.agentscope.core.message.ToolResultState.SUCCESS);
                } catch(Problem e) { return ToolResultBlock.error(e.getMessage()); }
            }).subscribeOn(Schedulers.boundedElastic());
        }
    }
}
