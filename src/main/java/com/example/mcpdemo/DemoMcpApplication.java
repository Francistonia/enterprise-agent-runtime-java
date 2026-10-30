package com.example.mcpdemo;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** Standalone example MCP service using the official SDK; all order data is synthetic. */
@SpringBootApplication(exclude=DataSourceAutoConfiguration.class)
public class DemoMcpApplication {
    public static void main(String[] args) {
        var app=new SpringApplication(DemoMcpApplication.class);
        app.setDefaultProperties(Map.of("spring.config.name","mcp-demo","server.port","8081","server.address","127.0.0.1"));
        app.run(args);
    }
    @Bean
    HttpServletStreamableServerTransportProvider transport() {
        return HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp")
                .jsonMapper(McpJsonMapper.getDefault()).build();
    }
    @Bean ServletRegistrationBean<HttpServletStreamableServerTransportProvider> servlet(HttpServletStreamableServerTransportProvider transport) {
        var servlet=new ServletRegistrationBean<>(transport,"/mcp");servlet.setAsyncSupported(true);return servlet;
    }
    @Bean(destroyMethod="closeGracefully")
    McpSyncServer server(HttpServletStreamableServerTransportProvider transport) {
        var tool=McpSchema.Tool.builder().name("get_order").description("Query a synthetic demo order by ID")
                .inputSchema(McpJsonMapper.getDefault(),"""
                    {"type":"object","properties":{"orderId":{"type":"string","minLength":1,"maxLength":40}},
                     "required":["orderId"],"additionalProperties":false}
                    """).build();
        return McpServer.sync(transport).serverInfo("demo-orders","1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .toolCall(tool,(exchange,request) -> {
                    Object id=request.arguments().get("orderId");
                    if(!(id instanceof String order) || !order.matches("[a-zA-Z0-9_-]{1,40}"))
                        return new McpSchema.CallToolResult("Invalid orderId",true);
                    return new McpSchema.CallToolResult(
                            "{\"orderId\":\""+order+"\",\"status\":\"SHIPPED\",\"synthetic\":true}",false);
                }).build();
    }
    @Bean SecurityFilterChain security(HttpSecurity http,Environment env) throws Exception {
        String expected="Bearer "+env.getProperty("RUNTIME_SECRET_DEMO_MCP","local-demo-token");
        var filter=new OncePerRequestFilter() {
            @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response,jakarta.servlet.FilterChain chain)
                    throws java.io.IOException,jakarta.servlet.ServletException {
                String supplied=request.getHeader("Authorization");
                if(supplied==null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) {
                    response.setStatus(401);response.setHeader("WWW-Authenticate","Bearer");return;
                }
                chain.doFilter(request,response);
            }
        };
        return http.csrf(c -> c.disable()).sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(c -> c.anyRequest().permitAll())
                .addFilterBefore(filter,BasicAuthenticationFilter.class).build();
    }
}
