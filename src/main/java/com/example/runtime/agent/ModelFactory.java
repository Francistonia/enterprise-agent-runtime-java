package com.example.runtime.agent;

import com.example.runtime.core.Problem;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ModelFactory {
    private final Environment env;
    public ModelFactory(Environment env) { this.env=env; }
    public Model create() {
        if("ark".equals(env.getProperty("runtime.model-provider","ark"))) {
            String key=env.getProperty("ARK_API_KEY","");
            if(key.isBlank()) throw Problem.unavailable("Configure ARK_API_KEY or use scripts/run-local.ps1");
            return OpenAIChatModel.builder().apiKey(key)
                    .baseUrl(env.getRequiredProperty("runtime.model-base-url"))
                    .modelName(env.getProperty("runtime.model","ark-code-latest"))
                    .stream(true).build();
        }
        String key=env.getProperty("DASHSCOPE_API_KEY","");
        if(key.isBlank()) throw Problem.unavailable("Configure DASHSCOPE_API_KEY to execute an agent");
        String model=env.getProperty("runtime.model","dashscope:qwen-plus");
        if(!model.startsWith("dashscope:")) throw Problem.bad("This build supports dashscope: models");
        return DashScopeChatModel.builder().apiKey(key).modelName(model.substring("dashscope:".length())).build();
    }
}
