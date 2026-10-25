package com.agentyard.platform.agent;

import static com.agentyard.platform.agent.AgentAuthorization.notFound;
import com.agentyard.contracts.agent.AgentContracts.ScopeKind;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

/** Runtime transcript storage is private; every read first passes live control-plane authorization. */
@Service
public class AgentHistoryClient {
    private final AgentService agents;
    private final String baseUrl,token;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public AgentHistoryClient(AgentService agents,@Value("${agentyard.agent-runtime.base-url}") String baseUrl,
        @Value("${agentyard.internal-auth.token}") String token) {this.agents=agents;this.baseUrl=baseUrl;this.token=token;}
    public Object history(String reader,String sessionId,long after,int limit,String query) {
        var session=agents.store().session(sessionId,false).orElseThrow(()->notFound("Session"));
        if(!reader.equals(session.participantId())) agents.authorization().require(agents.store(),reader,"session.history",ScopeKind.SESSION,sessionId,false);
        agents.authorization().active(agents.store(),reader);
        var uri=UriComponentsBuilder.fromUriString(baseUrl).pathSegment("sessions",sessionId,"history")
            .queryParam("participantId",session.participantId()).queryParam("after",Math.max(0,after)).queryParam("limit",Math.max(1,Math.min(200,limit)));
        if(query!=null&&!query.isBlank()) uri.queryParam("query",query);
        try {
            var response=http.send(HttpRequest.newBuilder(uri.build().encode().toUri()).timeout(Duration.ofSeconds(30))
                .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) throw new IllegalStateException("Session history service is unavailable");
            return agents.mapper().readValue(response.body(),Object.class);
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new IllegalStateException("history request interrupted");}
        catch(java.io.IOException error) {throw new IllegalStateException("Session history service is unavailable",error);}
    }
}
