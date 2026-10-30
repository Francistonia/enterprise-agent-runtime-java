package com.example.sandboxworker;

import com.example.runtime.core.*;
import com.example.runtime.sandbox.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

@SpringBootApplication
@Import({Json.class,Audit.class,Secrets.class,OutboundHttp.class,DockerProcessRunner.class,SandboxService.class,ApiErrors.class})
public class SandboxWorkerApplication {
    public static void main(String[] args) {
        var app=new SpringApplication(SandboxWorkerApplication.class);
        app.setDefaultProperties(Map.of("spring.config.name","sandbox-worker"));
        app.run(args);
    }
    @Bean SecurityFilterChain security(HttpSecurity http,Environment env) throws Exception {
        String token=env.getProperty("RUNTIME_SECRET_SANDBOX_WORKER","");
        if(token.isBlank()) throw new IllegalStateException("RUNTIME_SECRET_SANDBOX_WORKER is required");
        var filter=new OncePerRequestFilter() {
            protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response,jakarta.servlet.FilterChain chain)
                    throws java.io.IOException,jakarta.servlet.ServletException {
                String supplied=request.getHeader("Authorization");
                if(supplied==null || !MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) {response.setStatus(401);return;}
                chain.doFilter(request,response);
            }
        };
        return http.csrf(c -> c.disable()).sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(c -> c.anyRequest().permitAll())
                .addFilterBefore(filter,BasicAuthenticationFilter.class).build();
    }
    @RestController
    static class ExecuteController {
        record Command(String command,int timeoutSeconds) {}
        private final SandboxService sandbox;
        ExecuteController(SandboxService sandbox) {this.sandbox=sandbox;}
        @PostMapping("/execute") Object execute(@RequestBody Command input) {
            return sandbox.execute(new Actor("worker","runtime",Set.of("sandbox:execute"),Set.of(),null),
                    input.command(),input.timeoutSeconds());
        }
    }
}
