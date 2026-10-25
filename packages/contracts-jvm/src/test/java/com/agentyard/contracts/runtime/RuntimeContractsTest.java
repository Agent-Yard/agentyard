package com.agentyard.contracts.runtime;

import static com.agentyard.contracts.runtime.RuntimeContracts.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RuntimeContractsTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void messageContentUsesTheRuntimeBlockWireFormat() {
        String json="""
            {"blocks":[{"type":"TEXT","text":"Investigation complete"},
            {"type":"CARD","cardType":"investigation","version":"1","data":{"approved":true}}],
            "metadata":{"language":"en"}}
            """;
        var content=mapper.readValue(json,MessageContent.class);
        assertEquals(2,content.blocks().size());
        assertEquals(mapper.readTree(json),mapper.readTree(mapper.writeValueAsString(content)));
        assertThrows(UnsupportedOperationException.class,()->content.metadata().put("private","value"));
    }

    @Test void immutableConnectorVersionKeepsAccountReferenceAndExplicitRetryPolicy() {
        String json="""
            {"connectorType":"crm","accountSnapshot":{"accountId":"integration-account","externalSecretRef":"vault://opaque-reference"},
            "timeoutSeconds":30,"retryPolicy":{"mode":"EXPONENTIAL","maxAttempts":3,"initialDelayMs":100,
            "maxDelayMs":1000,"backoffMultiplier":2.0,"retryableCategories":["UNAVAILABLE"],"retryableErrorCodes":[]},
            "config":{"region":"asia"},"operationMappings":{"lookup":{"operation":"case.read"}}}
            """;
        var connector=mapper.readValue(json,ToolConnectorDescriptor.class);
        assertEquals("integration-account",connector.accountSnapshot().accountId());
        assertEquals(ToolConnectorRetryMode.EXPONENTIAL,connector.retryPolicy().mode());
        assertThrows(UnsupportedOperationException.class,()->connector.operationMappings().get("lookup").put("operation","case.delete"));
        assertEquals(mapper.readTree(json),mapper.readTree(mapper.writeValueAsString(connector)));
    }

    @Test void versionedPlaybookDefinitionAndToolReceiptRoundTripWithoutSessionOwnership() {
        var node=new PlaybookNode("review","Review",PlaybookNodeType.HUMAN_TASK,"Confirm evidence",null,null,null,null,Map.of(),null);
        var config=new PlaybookConfig("flow-version","Investigation","","{}","{}",new PlaybookExecutionPolicy("PT1M",null),
            true,true,"review",List.of(node),List.of());
        assertEquals(config,mapper.readValue(mapper.writeValueAsString(config),PlaybookConfig.class));
        var result=mapper.readValue("""
            {"statePatch":{"externalReceipt":"receipt-1"},"routeKey":"rejected","terminalStatus":"FAILED","failureReason":"permission revoked"}
            """,PlaybookToolTaskResult.class);
        assertEquals(PlaybookRunStatus.FAILED,result.terminalStatus());
        assertEquals("receipt-1",result.statePatch().get("externalReceipt"));
    }
}
