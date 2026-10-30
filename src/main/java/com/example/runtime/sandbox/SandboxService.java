package com.example.runtime.sandbox;

import com.example.runtime.core.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class SandboxService {
    private final ProcessRunner runner;
    private final Environment env;
    private final Audit audit;
    private final OutboundHttp http;
    private final Secrets secrets;
    private final Semaphore slots=new Semaphore(4);
    public SandboxService(ProcessRunner runner,Environment env,Audit audit) {this(runner,env,audit,null,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public SandboxService(ProcessRunner runner,Environment env,Audit audit,OutboundHttp http,Secrets secrets) {
        this.runner=runner;this.env=env;this.audit=audit;this.http=http;this.secrets=secrets;
    }
    public ProcessRunner.Result execute(Actor actor,String command,int seconds) {
        actor.require("sandbox:execute");
        if(!env.getProperty("runtime.sandbox.enabled",Boolean.class,false))
            throw Problem.unavailable("Sandbox is disabled; configure a Docker worker and SANDBOX_ENABLED=true");
        if(command==null || command.isBlank() || command.length()>16000 || seconds<1
                || seconds>env.getProperty("runtime.sandbox.max-seconds",Integer.class,30))
            throw Problem.bad("Invalid command or timeout");
        String worker=env.getProperty("runtime.sandbox.worker-url","");
        if(!worker.isBlank()) {
            String token=secrets.get("RUNTIME_SECRET_SANDBOX_WORKER");
            var response=http.json("POST",worker.replaceAll("/+$","")+"/execute",
                    java.util.Map.of("Authorization","Bearer "+token),
                    java.util.Map.of("command",command,"timeoutSeconds",seconds));
            try {return new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(response,ProcessRunner.Result.class);}
            catch(Exception e) {throw Problem.unavailable("Sandbox worker returned an invalid result");}
        }
        if(!env.matchesProfiles("local","test","sandbox-worker"))
            throw Problem.unavailable("Configure SANDBOX_WORKER_URL for production sandbox execution");
        String name="runtime-"+UUID.randomUUID();
        String image=env.getRequiredProperty("runtime.sandbox.image");
        if(image.startsWith("-") || image.isBlank()) throw Problem.bad("Invalid sandbox image");
        if(!slots.tryAcquire()) throw Problem.unavailable("Sandbox capacity is exhausted");
        int limit=env.getProperty("runtime.sandbox.max-output-bytes",Integer.class,65536);
        try {
            audit.record(actor,"sandbox.execute",name,"STARTED");
            var result=runner.run(List.of("docker","run","--interactive","--rm","--name",name,
                    "--network=none","--read-only","--cpus=1","--memory=256m","--memory-swap=256m",
                    "--pids-limit=64","--cap-drop=ALL","--security-opt=no-new-privileges",
                    "--user=65534:65534","--tmpfs=/tmp:rw,nosuid,nodev,size=32m,mode=1777",
                    "--tmpfs=/workspace:rw,nosuid,nodev,size=64m,mode=1777",
                    "--workdir=/workspace","--pull=never",image,"/bin/sh","-s"),
                    Duration.ofSeconds(seconds),limit,command+"\n");
            audit.record(actor,"sandbox.execute",name,result.timedOut()?"TIMEOUT":result.exitCode()==0?"SUCCESS":"ERROR");
            return result;
        } finally {
            // The Docker CLI can die while the container keeps running. Always remove it separately.
            boolean interrupted=Thread.interrupted();
            try { runner.run(List.of("docker","rm","--force",name),Duration.ofSeconds(5),1024); }
            catch(Exception ignored) { audit.record(actor,"sandbox.cleanup",name,"ERROR"); }
            finally { if(interrupted) Thread.currentThread().interrupt();slots.release(); }
        }
    }
}
