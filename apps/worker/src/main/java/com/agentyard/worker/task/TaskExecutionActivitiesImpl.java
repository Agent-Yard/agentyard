package com.agentyard.worker.task;

import com.agentyard.contracts.http.HttpUrls;
import com.agentyard.contracts.task.TaskContracts.*;
import com.agentyard.contracts.session.SessionContracts.AgentTurnExecutionOutcome;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

@Component
public class TaskExecutionActivitiesImpl implements TaskExecutionActivities {
    private final HttpClient client=HttpClient.newHttpClient();
    private final ObjectMapper mapper; private final String api,runtime,token;
    public TaskExecutionActivitiesImpl(ObjectMapper mapper,@Value("${agentyard.api.base-url:http://127.0.0.1:8080/api}") String api,
        @Value("${agentyard.agent-runtime.base-url}") String runtime,@Value("${agentyard.internal-auth.token}") String token) {
        this.mapper=mapper.rebuild().findAndAddModules().disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS).build();this.api=api;this.runtime=runtime;this.token=token;
    }
    public ExecutionInput taskExecutionInput(String id) { return call(api,"/internal/task-executions/"+id,null,ExecutionInput.class); }
    public Execution taskExecutionProgress(String id,ExecutionProgress progress) { return call(api,"/internal/task-executions/"+id+"/progress",progress,Execution.class); }
    public AgentTurnExecutionOutcome taskAgentExecute(String id,Map<String,Object> resume) {
        return call(runtime,"/task-executions/execute",Map.of("executionId",id,"resume",resume),AgentTurnExecutionOutcome.class);
    }
    private <T> T call(String base,String path,Object body,Class<T> type) {
        try {
            var request=HttpRequest.newBuilder(HttpUrls.join(base,path)).timeout(Duration.ofMinutes(15)).header("Authorization","Bearer "+token);
            if(body!=null) request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()<200 || response.statusCode()>=300) throw new IllegalStateException("task runtime HTTP "+response.statusCode()+": "+response.body());
            return mapper.readValue(response.body(),type);
        } catch(InterruptedException error) { Thread.currentThread().interrupt();throw new IllegalStateException("task execution interrupted",error); }
        catch(java.io.IOException error) { throw new IllegalStateException("task execution transport failed",error); }
    }
}
