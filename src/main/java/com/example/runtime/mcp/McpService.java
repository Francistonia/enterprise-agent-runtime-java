package com.example.runtime.mcp;

import com.example.runtime.core.*;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class McpService {
    public record Definition(@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{1,40}") String id,
                             @NotBlank String url, @NotEmpty Set<String> allowedTools,
                             @NotEmpty Set<String> readers, OutboundAuth.Credential credential) {}
    public record ToolDescription(String name, String description, Map<String, Object> schema) {}
    public record Binding(String id, String agentId, String mcpId, boolean enabled,
                          List<ToolDescription> tools) {}
    private final ResourceStore store;
    private final OutboundHttp http;
    private final OutboundAuth auth;
    private final Json json;
    private final Audit audit;
    public McpService(ResourceStore store, OutboundHttp http, OutboundAuth auth, Json json, Audit audit) {
        this.store = store; this.http = http; this.auth = auth; this.json = json; this.audit = audit;
    }
    public Definition register(Actor actor, Definition definition) {
        actor.require("mcp:manage");
        http.check(definition.url()); auth.validate(definition.credential());
        if (definition.allowedTools().stream().anyMatch(t -> !t.matches("[a-zA-Z0-9_-]{1,64}")))
            throw Problem.bad("Invalid allowed tool name");
        store.create(actor.tenant(), "mcp", definition.id(), definition);
        audit.record(actor, "mcp.register", definition.id(), "SUCCESS");
        return definition;
    }
    public List<Definition> catalog(Actor actor) {
        return store.list(actor.tenant(), "mcp", Definition.class).stream()
                .filter(d -> actor.can("mcp:manage") || actor.matches(d.readers())).toList();
    }
    public Definition definition(Actor actor, String id) {
        var d = store.get(actor.tenant(), "mcp", id, Definition.class);
        if (!actor.matches(d.readers()) && !actor.can("mcp:manage")) throw Problem.forbidden();
        return d;
    }
    private McpClientWrapper connect(Actor actor, Definition d) {
        http.check(d.url());
        var builder = McpClientBuilder.create(d.id()).streamableHttpTransport(d.url())
                .headers(auth.headers(actor, d.credential())).propagateMeta(false)
                .timeout(Duration.ofSeconds(20)).initializationTimeout(Duration.ofSeconds(10))
                .customizeStreamableHttpClient(c -> c.followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(5)));
        var client = builder.buildAsync().block(Duration.ofSeconds(10));
        if (client == null) throw Problem.unavailable("Could not create MCP connection");
        try { client.initialize().block(Duration.ofSeconds(12)); return client; }
        catch (Exception e) { client.close(); throw Problem.unavailable("MCP initialization failed"); }
    }
    public List<ToolDescription> discover(Actor actor, String id) {
        actor.require("mcp:manage");
        var d = definition(actor, id);
        try (var client = connect(actor, d)) {
            return Objects.requireNonNull(client.listTools().block(Duration.ofSeconds(20))).stream()
                    .filter(t -> d.allowedTools().contains(t.name()))
                    .map(t -> new ToolDescription(t.name(), t.description(),
                            json.mapper().convertValue(t.inputSchema(), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {})))
                    .toList();
        }
    }
    public Binding install(Actor actor, String agentId, String mcpId) {
        actor.require("mcp:manage");
        // Resolve the target agent in the same tenant before making any outbound request.
        store.get(actor.tenant(), "agent", agentId, com.example.runtime.agent.AgentService.Definition.class);
        var tools = discover(actor, mcpId);
        if (tools.isEmpty()) throw Problem.bad("MCP server exposes none of the approved tools");
        String id = bindingId(agentId, mcpId);
        var binding = new Binding(id, agentId, mcpId, true, tools);
        try {
            var old = store.versioned(actor.tenant(), "mcp-binding", id, Binding.class);
            store.save(actor.tenant(), "mcp-binding", id, binding, old.revision());
        } catch (Problem e) {
            if (e.status() != org.springframework.http.HttpStatus.NOT_FOUND) throw e;
            store.create(actor.tenant(), "mcp-binding", id, binding);
        }
        audit.record(actor, "mcp.install", id, "SUCCESS");
        return binding;
    }
    public void uninstall(Actor actor, String agentId, String mcpId) {
        actor.require("mcp:manage");
        String id = bindingId(agentId, mcpId);
        store.modify(actor.tenant(), "mcp-binding", id, Binding.class,
                b -> new Binding(b.id(), b.agentId(), b.mcpId(), false, b.tools()));
        audit.record(actor, "mcp.uninstall", id, "SUCCESS");
    }
    public List<Binding> bindings(Actor actor, String agentId) {
        return store.list(actor.tenant(), "mcp-binding", Binding.class).stream()
                .filter(b -> b.agentId().equals(agentId) && b.enabled()).toList();
    }
    public McpSchema.CallToolResult call(Actor actor, String agentId, String mcpId, String tool, Map<String,Object> args) {
        actor.require("mcp:invoke");
        var b = store.get(actor.tenant(), "mcp-binding", bindingId(agentId, mcpId), Binding.class);
        var d = definition(actor, mcpId);
        if (!b.enabled() || !d.allowedTools().contains(tool)
                || b.tools().stream().noneMatch(t -> t.name().equals(tool))) throw Problem.forbidden();
        audit.record(actor, "mcp.invoke", mcpId + "/" + tool, "STARTED");
        try (var client = connect(actor, d)) {
            // Recheck revocation after initialization, immediately before dispatch.
            if (!store.get(actor.tenant(), "mcp-binding", b.id(), Binding.class).enabled()) throw Problem.forbidden();
            var result = client.callTool(tool, args).block(Duration.ofSeconds(25));
            audit.record(actor, "mcp.invoke", mcpId + "/" + tool,
                    result != null && !Boolean.TRUE.equals(result.isError()) ? "SUCCESS" : "ERROR");
            return result;
        } catch (Problem e) { throw e; }
        catch (Exception e) {
            audit.record(actor, "mcp.invoke", mcpId + "/" + tool, "ERROR");
            throw Problem.unavailable("MCP tool invocation failed");
        }
    }
    private String bindingId(String agent, String mcp) {
        return UUID.nameUUIDFromBytes((agent + "\n" + mcp).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}
