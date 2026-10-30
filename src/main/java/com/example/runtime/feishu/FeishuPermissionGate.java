package com.example.runtime.feishu;

import com.example.runtime.core.*;
import com.example.runtime.knowledge.KnowledgeModels.Document;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FeishuPermissionGate {
    private final ResourceStore store;
    private final Secrets secrets;
    private final OutboundHttp http;
    public FeishuPermissionGate(ResourceStore store,Secrets secrets,OutboundHttp http) {
        this.store=store;this.secrets=secrets;this.http=http;
    }
    public boolean visible(Actor actor,Document document) {
        if(!document.sourceType().startsWith("FEISHU:")) return true;
        try {
            String key=UUID.nameUUIDFromBytes(actor.subject().getBytes(StandardCharsets.UTF_8)).toString();
            var account=store.get(actor.tenant(),"feishu-user",key,FeishuService.UserAccount.class);
            String token=secrets.get(account.tokenRef());
            String[] source=document.sourceToken().split(":",2);
            if(source.length!=2 || !source[1].matches("[a-zA-Z0-9_-]{1,256}")) return false;
            if("docx".equals(source[0])) {
                var result=http.json("GET","https://open.feishu.cn/open-apis/docx/v1/documents/"+source[1]+"/raw_content",
                        Map.of("Authorization","Bearer "+token),null);
                return result.path("code").asInt(-1)==0;
            }
            if("file".equals(source[0])) {
                byte[] result=http.bytes("GET","https://open.feishu.cn/open-apis/drive/v1/files/"+source[1]+"/download",
                        Map.of("Authorization","Bearer "+token),null,null,2*1024*1024);
                // Some Feishu endpoints return a JSON error with HTTP 200.
                String body=new String(result,StandardCharsets.UTF_8).trim();
                if(body.startsWith("{")) {
                    try { var data=http.parse(result);if(data.has("code"))return data.path("code").asInt(-1)==0; }
                    catch(Problem ignored) { }
                }
                return true;
            }
            return false;
        } catch(Exception e) { return false; }
    }
}
