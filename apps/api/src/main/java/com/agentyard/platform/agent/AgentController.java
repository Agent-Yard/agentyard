package com.agentyard.platform.agent;

import com.agentyard.contracts.agent.AgentContracts.*;
import com.agentyard.platform.auth.RequireRuntimeAccess;
import com.agentyard.platform.shared.ApiResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/agent")
@RequireRuntimeAccess
public class AgentController {
    private final AgentService agents;
    private final AgentHistoryClient history;
    public AgentController(AgentService agents,AgentHistoryClient history) { this.agents=agents;this.history=history; }
    @GetMapping("/entries") public ApiResponse<?> entries() { return ApiResponse.ok(agents.entries()); }
    @PostMapping("/reception") public ApiResponse<?> reception(@RequestBody(required=false) ReceptionRequest request) {
        return ApiResponse.ok(agents.reception(request==null?new ReceptionRequest(null):request));
    }
    @GetMapping("/reception/{id}") public ApiResponse<?> reception(@PathVariable String id,@RequestParam(required=false) String cursor,@RequestParam(required=false) String before) {
        if(cursor!=null&&before!=null) throw new IllegalArgumentException("choose one pagination direction");
        return ApiResponse.ok(agents.receptionDetail(agents.currentActor().id(),id,cursor,before));
    }
    @PostMapping("/messages") public ApiResponse<?> message(@RequestBody SendMessage request) { return ApiResponse.ok(agents.send(request)); }
    @GetMapping("/sessions/{id}") public ApiResponse<?> session(@PathVariable String id) { return ApiResponse.ok(agents.detail(id)); }
    @GetMapping("/sessions/{id}/status") public ApiResponse<?> status(@PathVariable String id,@RequestParam(required=false) String taskId) {
        return ApiResponse.ok(agents.executionStatus(agents.currentActor().id(),id,taskId));
    }
    public record Control(String requestId,SessionStatus status) {}
    @PostMapping("/sessions/{id}/control") public ApiResponse<?> control(@PathVariable String id,@RequestBody Control request) {
        return ApiResponse.ok(agents.control(id,request.requestId(),request.status()));
    }
    @GetMapping("/sessions/{id}/history") public ApiResponse<?> history(@PathVariable String id,@RequestParam(defaultValue="0") long after,
        @RequestParam(defaultValue="50") int limit,@RequestParam(required=false) String query) {
        return ApiResponse.ok(history.history(agents.currentActor().id(),id,after,limit,query));
    }
    @GetMapping(value="/reception/{id}/stream",produces="text/event-stream")
    public SseEmitter stream(@PathVariable String id) {
        String participant=agents.currentActor().id();
        var initial=agents.receptionDetail(participant,id,null);
        SseEmitter emitter=new SseEmitter(300000L);var open=new AtomicBoolean(true);
        emitter.onCompletion(()->open.set(false));emitter.onTimeout(()->{open.set(false);emitter.complete();});
        emitter.onError(error->open.set(false));
        Thread.startVirtualThread(()->{
            try {
                Reception previous=initial;
                emitter.send(SseEmitter.event().name("reception").data(initial));
                int idle=0;
                while(open.get()) {
                    Thread.sleep(1000);
                    // Polling the durable source is also the replay path after any process/Redis reconnect.
                    var current=agents.receptionDetail(participant,id,null);
                    if(!current.equals(previous)) {emitter.send(SseEmitter.event().name("reception").data(current));previous=current;idle=0;}
                    else if(++idle>=15) {emitter.send(SseEmitter.event().comment("keepalive"));idle=0;}
                }
            } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();emitter.complete();}
            catch(Exception error) {emitter.completeWithError(error);}
        });
        return emitter;
    }
}
