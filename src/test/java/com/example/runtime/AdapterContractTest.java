package com.example.runtime;

import com.example.runtime.core.*;
import com.example.runtime.feishu.*;
import com.example.runtime.knowledge.*;
import com.example.runtime.sandbox.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import static com.example.runtime.knowledge.KnowledgeModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdapterContractTest {
    final Json json=new Json(new ObjectMapper().findAndRegisterModules());
    final Actor alice=new Actor("tenant","alice",Set.of("*"),Set.of(), "incoming-user-token");
    @Test void ragflowNeverIssuesAnUnrestrictedRequestWhenAclIsEmpty() {
        var http=mock(OutboundHttp.class);
        var engine=new RagflowEngine(http,mock(OutboundAuth.class));
        assertThat(engine.search(alice,base(),Map.of(),"query",5)).isEmpty();
        verifyNoInteractions(http);
    }
    @Test void ragflowPassesAllowedDocumentIdsAndDiscardsUnexpectedResults() {
        var http=mock(OutboundHttp.class);var auth=mock(OutboundAuth.class);
        when(auth.headers(any(),any())).thenReturn(Map.of());
        when(http.json(eq("POST"),anyString(),anyMap(),any())).thenReturn(json.mapper().valueToTree(Map.of(
                "code",0,"data",Map.of("chunks",List.of(
                Map.of("id","c1","document_id","approved","content","allowed","similarity",0.8),
                Map.of("id","c2","document_id","unapproved","content","secret","similarity",0.99))))));
        var engine=new RagflowEngine(http,auth);
        var doc=new Document("doc","kb","Title","text","https://source",Set.of("*"),"g","approved","READY","","UPLOAD",null);
        var hits=engine.search(alice,base(),Map.of("doc",doc),"query",5);
        assertThat(hits).extracting(Hit::content).containsExactly("allowed");
        ArgumentCaptor<Object> body=ArgumentCaptor.forClass(Object.class);
        verify(http).json(eq("POST"),endsWith("/api/v1/retrieval"),anyMap(),body.capture());
        assertThat(json.write(body.getValue())).contains("approved").doesNotContain("unapproved");
    }
    @Test void ragflowHttp200WithBusinessErrorIsNotSuccess() {
        var http=mock(OutboundHttp.class);var auth=mock(OutboundAuth.class);
        when(auth.headers(any(),any())).thenReturn(Map.of());
        when(http.json(anyString(),anyString(),anyMap(),any())).thenReturn(json.mapper().valueToTree(Map.of("code",109)));
        var doc=new Document("doc","kb","Title","text",null,Set.of("*"),"g","approved","READY","","UPLOAD",null);
        assertThatThrownBy(() -> new RagflowEngine(http,auth).search(alice,base(),Map.of("doc",doc),"x",5))
                .isInstanceOf(Problem.class);
    }
    @Test void embeddingBatchOrderComesFromIndicesAndDimensionsAreChecked() {
        var http=mock(OutboundHttp.class);var secrets=mock(Secrets.class);
        when(secrets.get(anyString())).thenReturn("fake");
        MockEnvironment env=new MockEnvironment().withProperty("runtime.embedding.dimensions","16")
                .withProperty("runtime.embedding.url","https://embedding.test/embed");
        List<Double> one=new ArrayList<>(Collections.nCopies(16,0.0));one.set(0,1.0);
        List<Double> two=new ArrayList<>(Collections.nCopies(16,0.0));two.set(1,1.0);
        when(http.json(anyString(),anyString(),anyMap(),any())).thenReturn(json.mapper().valueToTree(Map.of("data",
                List.of(Map.of("index",1,"embedding",two),Map.of("index",0,"embedding",one)))));
        var service=new EmbeddingService(env,http,secrets);
        var vectors=service.embed(List.of("one","two"));
        assertThat(vectors.get(0)[0]).isEqualTo(1.0);
        assertThat(vectors.get(1)[1]).isEqualTo(1.0);
        when(http.json(anyString(),anyString(),anyMap(),any())).thenReturn(json.mapper().valueToTree(Map.of("data",
                List.of(Map.of("index",0,"embedding",List.of(1,2))))));
        assertThatThrownBy(() -> service.embed(List.of("one"))).isInstanceOf(Problem.class);
    }
    @Test void tokenExchangePreservesTheUserAndRequestsTheTargetAudience() {
        var http=mock(OutboundHttp.class);var secrets=mock(Secrets.class);
        when(secrets.get("RUNTIME_SECRET_OAUTH")).thenReturn("private-client-secret");
        when(http.bytes(eq("POST"),anyString(),anyMap(),any(),anyString(),anyInt())).thenAnswer(i -> {
            String body=new String((byte[])i.getArgument(3),java.nio.charset.StandardCharsets.UTF_8);
            assertThat(body).contains("subject_token=incoming-user-token","audience=target-agent","scope=agent%3Arun");
            return "{}".getBytes();
        });
        when(http.parse(any())).thenReturn(json.mapper().valueToTree(Map.of("access_token","exchanged-token","token_type","Bearer")));
        var credential=new OutboundAuth.Credential(OutboundAuth.Mode.TOKEN_EXCHANGE,null,"https://idp/token","runtime",
                "RUNTIME_SECRET_OAUTH","target-agent","agent:run");
        assertThat(new OutboundAuth(http,secrets).headers(alice,credential))
                .containsEntry("Authorization","Bearer exchanged-token");
    }
    @Test void outboundOriginCheckRejectsUserInfoAndLookalikeHosts() {
        var env=new MockEnvironment().withProperty("runtime.allowed-origins","https://approved.example");
        var http=new OutboundHttp(env,json);
        assertThat(http.check("https://approved.example/path").getHost()).isEqualTo("approved.example");
        assertThatThrownBy(() -> http.check("https://approved.example.evil/path")).isInstanceOf(Problem.class);
        assertThatThrownBy(() -> http.check("https://user:pass@approved.example/path")).isInstanceOf(Problem.class);
        assertThatThrownBy(() -> http.check("file:///etc/passwd")).isInstanceOf(Problem.class);
    }
    @Test void sandboxUsesContainerArgumentsAndCleansUpAfterTimeout() {
        var runner=mock(ProcessRunner.class);var audit=mock(Audit.class);
        when(runner.run(anyList(),any(),anyInt(),anyString())).thenReturn(new ProcessRunner.Result(124,"","",false,true));
        var env=new MockEnvironment().withProperty("runtime.sandbox.enabled","true").withProperty("runtime.sandbox.image","python:3.12-slim");
        env.setActiveProfiles("test");
        var service=new SandboxService(runner,env,audit);
        String command="echo '$HOME'; printf 'hello'";
        assertThat(service.execute(alice,command,5).timedOut()).isTrue();
        @SuppressWarnings("unchecked") ArgumentCaptor<List<String>> argv=ArgumentCaptor.forClass(List.class);
        verify(runner).run(argv.capture(),any(Duration.class),anyInt(),eq(command+"\n"));
        assertThat(argv.getAllValues().get(0)).contains("--network=none","--read-only","--cap-drop=ALL","--pull=never");
        assertThat(argv.getValue()).endsWith("/bin/sh","-s").doesNotContain(command);
        verify(runner).run(argThat(a -> a.subList(0,3).equals(List.of("docker","rm","--force"))),any(Duration.class),anyInt());
    }
    @Test void feishuEmptyPageWithHasMoreStillContinuesPagination() {
        var store=mock(ResourceStore.class);var knowledge=mock(KnowledgeService.class);
        var http=mock(OutboundHttp.class);var secrets=mock(Secrets.class);
        var b=new FeishuService.Binding("wiki","kb","123",null,"app","RUNTIME_SECRET_FEISHU",Set.of("user:alice"));
        when(store.get("tenant","feishu-binding","wiki",FeishuService.Binding.class)).thenReturn(b);
        when(store.list("tenant","document",Document.class)).thenReturn(List.of());
        when(secrets.get(anyString())).thenReturn("fake");
        when(http.json(eq("POST"),contains("/tenant_access_token/internal"),anyMap(),any()))
                .thenReturn(json.mapper().valueToTree(Map.of("code",0,"tenant_access_token","app-token")));
        when(http.json(eq("GET"),contains("/nodes?page_size=50"),anyMap(),isNull())).thenAnswer(i -> {
            String url=i.getArgument(1);
            return json.mapper().valueToTree(Map.of("code",0,"data",url.contains("page_token=next")?
                    Map.of("items",List.of(),"has_more",false):Map.of("items",List.of(),"has_more",true,"page_token","next")));
        });
        new FeishuService(store,knowledge,http,secrets,mock(Audit.class)).sync(alice,"wiki");
        verify(http).json(eq("GET"),contains("page_token=next"),anyMap(),isNull());
        verifyNoInteractions(knowledge);
    }
    @Test void expiredFeishuUserTokenFailsClosed() {
        var store=mock(ResourceStore.class);var secrets=mock(Secrets.class);var http=mock(OutboundHttp.class);
        when(store.get(eq("tenant"),eq("feishu-user"),anyString(),eq(FeishuService.UserAccount.class)))
                .thenReturn(new FeishuService.UserAccount("alice","RUNTIME_SECRET_USER"));
        when(secrets.get(anyString())).thenReturn("expired");
        when(http.json(anyString(),anyString(),anyMap(),any())).thenReturn(json.mapper().valueToTree(Map.of("code",99991663)));
        var doc=new Document("doc","kb","Title","text",null,Set.of("*"),"g",null,"READY","","FEISHU:wiki","docx:token");
        assertThat(new FeishuPermissionGate(store,secrets,http).visible(alice,doc)).isFalse();
    }
    @Test void unicodeChunkingKeepsSupplementaryCharactersIntact() {
        String text="年假😀说明".repeat(600);
        var chunks=new Chunker().split(text);
        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.codePointCount(0,chunk.length())).isLessThanOrEqualTo(900);
            assertThat(Character.isLowSurrogate(chunk.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length()-1))).isFalse();
        });
    }
    private KnowledgeBase base() {
        return new KnowledgeBase("kb",Engine.RAGFLOW,Set.of("*"),"https://ragflow.test","dataset",null,"ragflow-managed");
    }
}
