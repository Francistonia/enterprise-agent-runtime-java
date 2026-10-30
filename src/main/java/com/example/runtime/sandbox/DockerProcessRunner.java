package com.example.runtime.sandbox;

import com.example.runtime.core.Problem;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

@Component
public class DockerProcessRunner implements ProcessRunner {
    private record Output(String text,boolean truncated) {}
    @Override
    public Result run(List<String> argv,Duration timeout,int maxBytes) {
        return run(argv,timeout,maxBytes,null);
    }
    @Override
    public Result run(List<String> argv,Duration timeout,int maxBytes,String stdin) {
        ExecutorService readers=Executors.newFixedThreadPool(3,r -> {
            Thread t=new Thread(r,"sandbox-output");t.setDaemon(true);return t;
        });
        Process process=null;
        try {
            process=new ProcessBuilder(argv).start();
            Process running=process;
            Future<Output> stdout=readers.submit(() -> drain(running.getInputStream(),maxBytes));
            Future<Output> stderr=readers.submit(() -> drain(running.getErrorStream(),maxBytes));
            Future<?> writer=readers.submit(() -> {
                try(var output=running.getOutputStream()) {
                    if(stdin!=null) output.write(stdin.getBytes(StandardCharsets.UTF_8));
                } catch(IOException e) { throw new UncheckedIOException(e); }
            });
            boolean ended=process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS);
            if(!ended) {
                process.destroyForcibly();
                process.waitFor(2,TimeUnit.SECONDS);
            }
            Output out=stdout.get(3,TimeUnit.SECONDS),err=stderr.get(3,TimeUnit.SECONDS);
            if(ended && process.exitValue()==0) writer.get(1,TimeUnit.SECONDS);
            return new Result(ended?process.exitValue():124,out.text(),err.text(),
                    out.truncated()||err.truncated(),!ended);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();throw Problem.unavailable("Sandbox execution interrupted");
        } catch(Exception e) { throw Problem.unavailable("Sandbox worker could not execute the container"); }
        finally {
            if(process!=null && process.isAlive()) process.destroyForcibly();
            readers.shutdownNow();
        }
    }
    private Output drain(InputStream input,int maxBytes) throws IOException {
        try(input;var out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];
            boolean truncated=false;
            int count;
            while((count=input.read(buffer))!=-1) {
                int accepted=Math.min(count,Math.max(0,maxBytes-out.size()));
                out.write(buffer,0,accepted);
                if(accepted<count) truncated=true;
            }
            return new Output(out.toString(StandardCharsets.UTF_8),truncated);
        }
    }
}
