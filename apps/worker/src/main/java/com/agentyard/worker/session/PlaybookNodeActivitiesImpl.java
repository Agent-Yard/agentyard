package com.agentyard.worker.session;

import com.agentyard.contracts.session.SessionContracts.PlaybookRunStatus;
import com.agentyard.contracts.session.SessionContracts.PlaybookToolTaskRequest;
import com.agentyard.contracts.session.SessionContracts.PlaybookToolTaskResult;
import com.agentyard.worker.runtime.SandboxGateway;
import com.agentyard.worker.runtime.SandboxGateway.SandboxExecutionResult;
import com.agentyard.worker.runtime.SessionAgentRuntimeGateway;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class PlaybookNodeActivitiesImpl implements PlaybookNodeActivities {
    private static final String RESULT_MARKER = "__AGENTYARD_RESULT__";
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };

    private final SandboxGateway sandboxGateway;
    private final SessionAgentRuntimeGateway runtimeGateway;
    private final ObjectMapper objectMapper;

    public PlaybookNodeActivitiesImpl(
        SandboxGateway sandboxGateway,
        SessionAgentRuntimeGateway runtimeGateway,
        ObjectMapper objectMapper
    ) {
        this.sandboxGateway = sandboxGateway;
        this.runtimeGateway = runtimeGateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public PlaybookNodeExecutionResult executeStep(PlaybookNodeExecutionRequest request) {
        String scriptRef = stringValue(request.scriptRef(), null);
        String scriptVersion = stringValue(request.scriptVersion(), null);
        if (scriptRef == null || scriptVersion == null) {
            return failed("playbook step must define scriptRef and scriptVersion");
        }
        try {
            Map<String, Object> scriptConfig = resolveVersionedScriptConfig(request);
            String runtime = stringValue(scriptConfig.get("runtime"), "python").toLowerCase();
            String code = stringValue(scriptConfig.get("code"), null);
            if (code == null || code.isBlank()) {
                return failed("playbook step " + scriptRef + "@" + scriptVersion + " is missing versioned script code");
            }
            SandboxExecutionResult execution = switch (runtime) {
                case "python", "python3", "jupyter" -> sandboxGateway.executePython(
                    buildPythonScript(request, scriptConfig, code),
                    stringValue(scriptConfig.get("kernelName"), "python3.11"),
                    intValue(scriptConfig.get("timeoutSeconds"), 30)
                );
                case "node", "nodejs", "javascript" -> sandboxGateway.executeNode(
                    buildNodeScript(request, scriptConfig, code),
                    intValue(scriptConfig.get("timeoutSeconds"), 30)
                );
                default -> throw new IllegalStateException("unsupported sandbox runtime: " + runtime);
            };
            if (!"ok".equalsIgnoreCase(execution.status())) {
                return failed(execution.stderr().isBlank() ? execution.stdout() : execution.stderr());
            }
            Map<String, Object> result = extractMarkedResult(execution.stdout());
            return normalizeStepResult(request, result);
        } catch (RuntimeException error) {
            return failed(error.getMessage() == null ? "playbook step failed" : error.getMessage());
        }
    }

    @Override
    public PlaybookNodeExecutionResult executeTool(PlaybookNodeExecutionRequest request) {
        try {
            PlaybookToolTaskResult result = runtimeGateway.executePlaybookToolTask(
                new PlaybookToolTaskRequest(
                    request.sessionId(),
                    request.playbookRunId(),
                    request.playbookId(),
                    request.nodeKey(),
                    request.nodeName(),
                    request.ownerAgent(),
                    request.toolId(),
                    request.toolOperation(),
                    request.input(),
                    request.config()
                )
            );
            if (result.terminalStatus() == PlaybookRunStatus.CANCELLED) {
                return failed("playbook tool task cannot emit CANCELLED directly");
            }
            return new PlaybookNodeExecutionResult(
                result.statePatch(),
                result.routeKey(),
                result.terminalStatus(),
                result.failureReason()
            );
        } catch (RuntimeException error) {
            return failed(error.getMessage() == null ? "playbook tool task failed" : error.getMessage());
        }
    }

    private PlaybookNodeExecutionResult normalizeStepResult(
        PlaybookNodeExecutionRequest request,
        Map<String, Object> result
    ) {
        Map<String, Object> statePatch = statePatchFromResult(request, result);
        String routeKey = firstNonBlank(
            stringValue(result.get("routeKey"), null),
            stringValue(request.config().get("routeKey"), null)
        );
        PlaybookRunStatus terminalStatus = parseStatus(result.get("terminalStatus"));
        if (terminalStatus == PlaybookRunStatus.CANCELLED) {
            return failed("playbook step cannot emit CANCELLED directly");
        }
        String failureReason = firstNonBlank(
            stringValue(result.get("failureReason"), null),
            terminalStatus == null ? null : "playbook step failed"
        );
        return new PlaybookNodeExecutionResult(statePatch, routeKey, terminalStatus, failureReason);
    }

    private Map<String, Object> statePatchFromResult(
        PlaybookNodeExecutionRequest request,
        Map<String, Object> result
    ) {
        Object explicitPatch = result.get("statePatch");
        if (explicitPatch instanceof Map<?, ?> patchMap) {
            return toStringObjectMap(patchMap);
        }
        String outputKey = stringValue(request.config().get("outputKey"), null);
        if (outputKey != null && !outputKey.isBlank()) {
            return Map.of(outputKey, result);
        }
        return result;
    }

    private Map<String, Object> resolveVersionedScriptConfig(PlaybookNodeExecutionRequest request) {
        Object rawScriptVersions = request.config().get("scriptVersions");
        if (!(rawScriptVersions instanceof Map<?, ?> scriptVersions)) {
            throw new IllegalStateException("playbook step config.scriptVersions must be an object");
        }
        Object rawVersionConfig = scriptVersions.get(request.scriptVersion());
        if (!(rawVersionConfig instanceof Map<?, ?> versionConfig)) {
            throw new IllegalStateException(
                "playbook step " + request.scriptRef() + "@" + request.scriptVersion() + " is missing versioned script config"
            );
        }
        return toStringObjectMap(versionConfig);
    }

    private String buildPythonScript(PlaybookNodeExecutionRequest request, Map<String, Object> scriptConfig, String code) {
        return """
            import json
            input_data = json.loads(%s)
            node_config = json.loads(%s)
            script_config = json.loads(%s)
            script_identity = json.loads(%s)
            script_ref = script_identity["scriptRef"]
            script_version = script_identity["scriptVersion"]
            result = {"statePatch": {}, "routeKey": None}
            %s
            print(%s + json.dumps(result, ensure_ascii=False))
            """.formatted(
            pythonString(request.input()),
            pythonString(request.config()),
            pythonString(scriptConfig),
            pythonString(Map.of("scriptRef", request.scriptRef(), "scriptVersion", request.scriptVersion())),
            code,
            pythonString(RESULT_MARKER)
        );
    }

    private String buildNodeScript(PlaybookNodeExecutionRequest request, Map<String, Object> scriptConfig, String code) {
        return """
            const inputData = JSON.parse(%s);
            const nodeConfig = JSON.parse(%s);
            const scriptConfig = JSON.parse(%s);
            const scriptIdentity = JSON.parse(%s);
            const scriptRef = scriptIdentity.scriptRef;
            const scriptVersion = scriptIdentity.scriptVersion;
            let result = { statePatch: {}, routeKey: null };
            %s
            console.log(%s + JSON.stringify(result));
            """.formatted(
            javascriptString(request.input()),
            javascriptString(request.config()),
            javascriptString(scriptConfig),
            javascriptString(Map.of("scriptRef", request.scriptRef(), "scriptVersion", request.scriptVersion())),
            code,
            javascriptString(RESULT_MARKER)
        );
    }

    private Map<String, Object> extractMarkedResult(String output) {
        if (output == null) {
            throw new IllegalStateException("sandbox step did not emit structured result marker");
        }
        int markerIndex = output.lastIndexOf(RESULT_MARKER);
        if (markerIndex < 0) {
            throw new IllegalStateException("sandbox step did not emit structured result marker");
        }
        String jsonPayload = output.substring(markerIndex + RESULT_MARKER.length()).trim();
        int newlineIndex = jsonPayload.indexOf('\n');
        if (newlineIndex >= 0) {
            jsonPayload = jsonPayload.substring(0, newlineIndex).trim();
        }
        try {
            return objectMapper.readValue(jsonPayload, OBJECT_MAP);
        } catch (JacksonException error) {
            throw new IllegalStateException("sandbox step returned invalid structured result", error);
        }
    }

    private String pythonString(Object value) {
        try {
            return objectMapper.writeValueAsString(objectMapper.writeValueAsString(value == null ? Map.of() : value));
        } catch (JacksonException error) {
            throw new IllegalStateException("failed to serialize python sandbox payload", error);
        }
    }

    private String javascriptString(Object value) {
        return pythonString(value);
    }

    private Map<String, Object> toStringObjectMap(Map<?, ?> source) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key instanceof String stringKey) {
                result.put(stringKey, value);
            }
        });
        return result;
    }

    private PlaybookNodeExecutionResult failed(String reason) {
        return new PlaybookNodeExecutionResult(
            Map.of(),
            null,
            PlaybookRunStatus.FAILED,
            firstNonBlank(reason, "playbook node failed")
        );
    }

    private PlaybookRunStatus parseStatus(Object value) {
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            return null;
        }
        return PlaybookRunStatus.valueOf(stringValue.trim().toUpperCase());
    }

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String stringValue(Object value, String defaultValue) {
        return value instanceof String string && !string.isBlank() ? string : defaultValue;
    }

    private String firstNonBlank(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return fallback;
    }
}
