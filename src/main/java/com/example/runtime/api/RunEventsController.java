package com.example.runtime.api;

import com.example.runtime.agent.RunService;
import com.example.runtime.core.*;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class RunEventsController {
    private final RunService runs;
    private final Semaphore subscriptions=new Semaphore(32);
    private final ScheduledExecutorService executor=Executors.newScheduledThreadPool(2,r -> {
        Thread t=new Thread(r,"run-events");t.setDaemon(true);return t;
    });
    public RunEventsController(RunService runs) {this.runs=runs;}
    @GetMapping(value="/api/runs/{id}/events",produces="text/event-stream")
    public SseEmitter events(Authentication auth,@PathVariable String id) {
        Actor actor=Actor.from(auth);runs.get(actor,id);
        if(!subscriptions.tryAcquire()) throw Problem.unavailable("Too many event subscriptions");
        SseEmitter emitter=new SseEmitter(30000L);
        AtomicReference<ScheduledFuture<?>> future=new AtomicReference<>();
        var closed=new java.util.concurrent.atomic.AtomicBoolean();
        Runnable close=() -> {
            if(closed.compareAndSet(false,true)) {
                var scheduled=future.get();if(scheduled!=null)scheduled.cancel(false);
                subscriptions.release();
            }
        };
        emitter.onCompletion(close);emitter.onTimeout(() -> {close.run();emitter.complete();});
        emitter.onError(e -> close.run());
        AtomicReference<String> revision=new AtomicReference<>("");
        future.set(executor.scheduleAtFixedRate(() -> {
            if(closed.get())return;
            try {
                var run=runs.get(actor,id);
                if(!run.updatedAt().equals(revision.getAndSet(run.updatedAt())))
                    emitter.send(SseEmitter.event().name("run").id(run.updatedAt()).data(run));
                if(run.terminal()) {close.run();emitter.complete();}
            } catch(Exception e) {close.run();emitter.completeWithError(e);}
        },100,500,TimeUnit.MILLISECONDS));
        return emitter;
    }
    @PreDestroy public void close() {executor.shutdownNow();}
}
