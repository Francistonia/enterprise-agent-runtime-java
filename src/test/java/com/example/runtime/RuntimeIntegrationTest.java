package com.example.runtime;

import com.example.mcpdemo.DemoMcpApplication;
import com.example.runtime.agent.*;
import com.example.runtime.core.*;
import com.example.runtime.knowledge.*;
import com.example.runtime.mcp.McpService;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=RuntimeApplication.class,properties={
        "spring.datasource.url=jdbc:h2:mem:integration;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "runtime.run-timeout-seconds=10"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class RuntimeIntegrationTest {
    static final ConfigurableApplicationContext DEMO=new SpringApplicationBuilder(DemoMcpApplication.class)
            .run("--spring.config.name=mcp-demo","--server.port=0","--RUNTIME_SECRET_DEMO_MCP=test-token");
    static final int MCP_PORT=((ServletWebServerApplicationContext)DEMO).getWebServer().getPort();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("runtime.allowed-origins",() -> "http://127.0.0.1:"+MCP_PORT);
        registry.add("RUNTIME_SECRET_DEMO_MCP",() -> "test-token");
    }
    @Autowired MockMvc mvc;
    @Autowired Json json;
    @Autowired AgentService agents;
    @Autowired KnowledgeService knowledge;
    @Autowired ResourceStore store;
    @Autowired McpService mcp;
    @MockitoBean ModelFactory models;
    static final AtomicInteger IDS=new AtomicInteger();
    static final Actor ADMIN=new Actor("local","admin",Set.of("*"),Set.of(),null);
    static final Actor ALICE=new Actor("local","alice",Set.of("agent:run","kb:read","mcp:invoke","a2a:invoke"),Set.of(),null);
    static final Actor BOB=new Actor("local","bob",Set.of("agent:run","kb:read","mcp:invoke","a2a:invoke"),Set.of(),null);
    @BeforeEach void model() { when(models.create()).thenAnswer(i -> scriptedModel()); }
    @AfterAll static void stopDemo() { DEMO.close(); }

    private Model scriptedModel() {
        AtomicInteger step=new AtomicInteger();
        return new Model() {
            public String getModelName() {return "deterministic-test";}
            public Flux<ChatResponse> stream(List<Msg> messages,List<ToolSchema> tools,GenerateOptions options) {
                String text=messages.stream().filter(m -> m instanceof UserMessage).reduce((a,b)->b).orElseThrow().getTextContent();
                ContentBlock content;
                if(step.getAndIncrement()==0 && text.startsWith("安装 "))
                    content=ToolUseBlock.builder().id("call-1").name("install_mcp").input(Map.of("mcpId",text.substring(3)))
                            .content(json.write(Map.of("mcpId",text.substring(3)))).build();
                else if(step.get()==1 && text.startsWith("卸载 "))
                    content=ToolUseBlock.builder().id("call-1").name("uninstall_mcp").input(Map.of("mcpId",text.substring(3)))
                            .content(json.write(Map.of("mcpId",text.substring(3)))).build();
                else if(step.get()==1 && text.startsWith("搜索 "))
                    content=ToolUseBlock.builder().id("call-1").name("search_knowledge")
                            .input(Map.of("kbId",text.substring(3),"query","年假"))
                            .content(json.write(Map.of("kbId",text.substring(3),"query","年假"))).build();
                else content=TextBlock.builder().text("测试回复").build();
                return Flux.just(ChatResponse.builder().id(UUID.randomUUID().toString()).content(List.of(content))
                        .finishReason(content instanceof ToolUseBlock?"tool_calls":"stop").build());
            }
        };
    }
    private String unique(String prefix) {return prefix+IDS.incrementAndGet();}
    private String createAgent(Set<String> kbIds) {
        String id=unique("agent");
        agents.create(ADMIN,new AgentService.Definition(id,"Use the requested tool.",Set.of("*"),kbIds,Set.of(),false,5));
        return id;
    }
    private String createKb() {
        String id=unique("kb");
        knowledge.create(ADMIN,new KnowledgeModels.CreateKb(id,KnowledgeModels.Engine.CUSTOM,Set.of("*"),null,null,null));
        return id;
    }
    private JsonNode request(MockHttpServletRequestBuilder builder,String user,int status) throws Exception {
        String password="admin".equals(user)?"local-admin":"local-user";
        var response=mvc.perform(builder.with(httpBasic(user,password))).andExpect(status().is(status)).andReturn().getResponse();
        return response.getContentAsString().isBlank()?json.mapper().nullNode():json.mapper().readTree(response.getContentAsString());
    }
    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request,Object value) {
        return request.contentType("application/json").content(json.write(value));
    }
    private JsonNode await(String runId,String user) throws Exception {
        for(int i=0;i<200;i++) {
            var result=request(get("/api/runs/"+runId),user,200);
            if(Set.of("COMPLETED","FAILED","CANCELLED").contains(result.path("state").asText())) return result;
            Thread.sleep(50);
        }
        throw new AssertionError("Run did not finish");
    }
    @Test void authenticationAndManagementScopesAreEnforced() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        request(body(post("/api/agents"),new AgentService.Definition("denied","x",Set.of("*"),Set.of(),Set.of(),false,5)),"alice",403);
        var me=request(get("/api/me"),"alice",200);
        assertThat(me.path("tenant").asText()).isEqualTo("local");
        assertThat(me.toString()).doesNotContain("password","bearer");
    }
    @Test void documentAclRevocationAndDeletionAffectSearchImmediately() {
        String kb=createKb();
        knowledge.ingest(ADMIN,kb,new KnowledgeModels.Ingest("leave","年假制度","员工每年享有十天带薪年假。","https://example.com/leave",Set.of("user:alice")));
        assertThat(knowledge.search(ALICE,kb,"年假",5)).hasSize(1);
        assertThat(knowledge.search(BOB,kb,"年假",5)).isEmpty();
        knowledge.changeReaders(ADMIN,kb,"leave",Set.of("user:bob"));
        assertThat(knowledge.search(ALICE,kb,"年假",5)).isEmpty();
        assertThat(knowledge.search(BOB,kb,"年假",5)).hasSize(1);
        knowledge.delete(ADMIN,kb,"leave");
        assertThat(knowledge.search(BOB,kb,"年假",5)).isEmpty();
    }
    @Test void tenantsAreIsolatedEvenWhenResourceIdsMatch() {
        String kb=createKb();
        Actor other=new Actor("other","alice",Set.of("*"),Set.of(),null);
        assertThatThrownBy(() -> knowledge.readable(other,kb)).isInstanceOf(Problem.class)
                .satisfies(e -> assertThat(((Problem)e).status().value()).isEqualTo(404));
    }
    @Test void documentReplacementPublishesOnlyTheNewGeneration() {
        String kb=createKb();
        knowledge.ingest(ADMIN,kb,new KnowledgeModels.Ingest("doc","制度","旧版本。",null,Set.of("*")));
        knowledge.ingest(ADMIN,kb,new KnowledgeModels.Ingest("doc","制度","新版本。",null,Set.of("*")));
        assertThat(knowledge.search(ALICE,kb,"版本",10)).extracting(KnowledgeModels.Hit::content).containsExactly("新版本。");
    }
    @Test void feishuDocumentsWithoutLinkedUserAuthorizationStayInvisible() {
        String kb=createKb();
        knowledge.ingestSource(ADMIN,kb,new KnowledgeModels.Ingest("doc","飞书","保密内容",null,Set.of("*")),"FEISHU:binding","docx:token");
        assertThat(knowledge.search(ALICE,kb,"内容",5)).isEmpty();
    }
    @Test void actualMcpServerDiscoveryInvocationAndRevocationWork() throws Exception {
        String agent=createAgent(Set.of()),connector=unique("orders");
        mcp.register(ADMIN,new McpService.Definition(connector,"http://127.0.0.1:"+MCP_PORT+"/mcp",
                Set.of("get_order"),Set.of("*"),new OutboundAuth.Credential(OutboundAuth.Mode.BEARER,
                "RUNTIME_SECRET_DEMO_MCP",null,null,null,null,null)));
        assertThat(mcp.discover(ADMIN,connector)).extracting(McpService.ToolDescription::name).containsExactly("get_order");
        mcp.install(ADMIN,agent,connector);
        var result=request(body(post("/api/agents/"+agent+"/mcp/"+connector+"/tools/get_order"),
                Map.of("arguments",Map.of("orderId","TEST-42"))),"alice",200);
        assertThat(result.toString()).contains("TEST-42","SHIPPED");
        mcp.uninstall(ADMIN,agent,connector);
        request(body(post("/api/agents/"+agent+"/mcp/"+connector+"/tools/get_order"),
                Map.of("arguments",Map.of("orderId","TEST-42"))),"alice",403);
    }
    @Test void unapprovedMcpOriginsAndToolsAreRejected() {
        String agent=createAgent(Set.of());
        assertThatThrownBy(() -> mcp.register(ADMIN,new McpService.Definition(unique("bad"),
                "http://169.254.169.254/latest/meta-data",Set.of("get_order"),Set.of("*"),null))).isInstanceOf(Problem.class);
        assertThatThrownBy(() -> mcp.call(ALICE,agent,"missing","delete_order",Map.of())).isInstanceOf(Problem.class);
    }
    @Test void realHarnessRunsAsynchronouslyAndProtectsTaskOwnership() throws Exception {
        String agent=createAgent(Set.of());
        var run=request(body(post("/api/agents/"+agent+"/runs"),Map.of("message","hello")),"alice",202);
        var completed=await(run.path("id").asText(),"alice");
        assertThat(completed.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("output").asText()).isEqualTo("测试回复");
        request(get("/api/runs/"+run.path("id").asText()),"bob",403);
        request(get("/api/sessions/"+run.path("sessionId").asText()),"bob",403);
    }
    @Test void harnessInvokesNaturalLanguageMcpInstallAndUninstallTools() throws Exception {
        String agent=createAgent(Set.of()),connector=unique("natural");
        mcp.register(ADMIN,new McpService.Definition(connector,"http://127.0.0.1:"+MCP_PORT+"/mcp",
                Set.of("get_order"),Set.of("*"),new OutboundAuth.Credential(OutboundAuth.Mode.BEARER,
                "RUNTIME_SECRET_DEMO_MCP",null,null,null,null,null)));
        var install=request(body(post("/api/agents/"+agent+"/runs"),Map.of("message","安装 "+connector)),"admin",202);
        assertThat(await(install.path("id").asText(),"admin").path("state").asText()).isEqualTo("COMPLETED");
        assertThat(mcp.bindings(ADMIN,agent)).hasSize(1);
        var remove=request(body(post("/api/agents/"+agent+"/runs"),Map.of("message","卸载 "+connector)),"admin",202);
        assertThat(await(remove.path("id").asText(),"admin").path("state").asText()).isEqualTo("COMPLETED");
        assertThat(mcp.bindings(ADMIN,agent)).isEmpty();
    }
    @Test void revokedKnowledgeIsRemovedFromStoredAnswersAndFutureContext() throws Exception {
        String kb=createKb(),agent=createAgent(Set.of(kb));
        knowledge.ingest(ADMIN,kb,new KnowledgeModels.Ingest("doc","年假","年假十天",null,Set.of("user:alice")));
        var run=request(body(post("/api/agents/"+agent+"/runs"),Map.of("message","搜索 "+kb)),"alice",202);
        var complete=await(run.path("id").asText(),"alice");
        assertThat(complete.path("evidence").size()).isEqualTo(1);
        knowledge.changeReaders(ADMIN,kb,"doc",Set.of("user:bob"));
        var revoked=request(get("/api/runs/"+run.path("id").asText()),"alice",200);
        assertThat(revoked.path("output").isNull()).isTrue();
        assertThat(request(get("/api/sessions/"+run.path("sessionId").asText()),"alice",200).path("turns").size()).isZero();
    }
    @Test void idempotencyPreventsDuplicateAgentExecution() throws Exception {
        String agent=createAgent(Set.of());
        var first=request(body(post("/api/agents/"+agent+"/runs").header("Idempotency-Key","same"),Map.of("message","hello")),"alice",202);
        var next=request(body(post("/api/agents/"+agent+"/runs").header("Idempotency-Key","same"),Map.of("message","hello")),"alice",202);
        assertThat(next.path("id").asText()).isEqualTo(first.path("id").asText());
        await(first.path("id").asText(),"alice");
        request(body(post("/api/agents/"+agent+"/runs").header("Idempotency-Key","same"),Map.of("message","different")),"alice",409);
    }
    @Test void a2aMessageTaskAndDuplicateMessageIdAreInteroperable() throws Exception {
        String agent=createAgent(Set.of());
        Map<String,Object> params=Map.of("message",Map.of("kind","message","role","user","messageId","a2a-message-1",
                "parts",List.of(Map.of("kind","text","text","hello"))));
        var rpc=Map.of("jsonrpc","2.0","id","request-1","method","message/send","params",params);
        var first=request(body(post("/agents/"+agent+"/a2a"),rpc),"alice",200);
        var again=request(body(post("/agents/"+agent+"/a2a"),rpc),"alice",200);
        assertThat(again.path("result").path("id").asText()).isEqualTo(first.path("result").path("id").asText());
        String id=first.path("result").path("id").asText();
        assertThat(await(id,"alice").path("state").asText()).isEqualTo("COMPLETED");
        var task=request(body(post("/agents/"+agent+"/a2a"),Map.of("jsonrpc","2.0","id","q",
                "method","tasks/get","params",Map.of("id",id))),"alice",200);
        assertThat(task.path("result").path("status").path("state").asText()).isEqualTo("completed");
    }
    @Test void sandboxIsDisabledUntilExplicitlyConfigured() throws Exception {
        var response=request(body(post("/api/sandbox/execute"),Map.of("command","echo test","timeoutSeconds",5)),"alice",503);
        assertThat(response.path("error").asText()).contains("disabled");
        request(body(post("/api/sandbox/execute"),Map.of("command","echo test","timeoutSeconds",5)),"bob",403);
    }
    @Test void optimisticUpdatesRejectLostWrites() {
        String id=unique("cas");
        store.create("local","test",id,Map.of("value",1));
        store.save("local","test",id,Map.of("value",2),1);
        assertThatThrownBy(() -> store.save("local","test",id,Map.of("value",3),1)).isInstanceOf(Problem.class);
    }
}
