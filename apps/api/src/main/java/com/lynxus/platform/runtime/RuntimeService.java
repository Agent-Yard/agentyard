package com.lynxus.platform.runtime;

import static com.lynxus.platform.runtime.RuntimeDtos.*;

import com.lynxus.platform.adapters.ResourceAdapters.KnowledgeBaseProvider;
import com.lynxus.platform.adapters.ResourceAdapters.SkillExecutor;
import com.lynxus.contracts.runtime.WorkflowContracts.NodeStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.TaskStatus;
import com.lynxus.contracts.runtime.WorkflowContracts.WorkflowStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RuntimeService {
    private final KnowledgeBaseProvider knowledgeBaseProvider;
    private final SkillExecutor skillExecutor;
    private final List<TaskInstanceDto> tasks = new ArrayList<>();
    private final List<WorkflowInstanceDto> workflows = new ArrayList<>();

    public RuntimeService(KnowledgeBaseProvider knowledgeBaseProvider, SkillExecutor skillExecutor) {
        this.knowledgeBaseProvider = knowledgeBaseProvider;
        this.skillExecutor = skillExecutor;
        seed();
    }

    public List<TaskInstanceDto> listTasks() {
        return tasks;
    }

    public List<WorkflowInstanceDto> listWorkflows() {
        return workflows;
    }

    public TaskInstanceDto launchTask(TaskLaunchRequest request) {
        String taskId = nextId("task");
        String workflowId = nextId("wf");
        List<String> knowledge = knowledgeBaseProvider.retrieve(request.scenarioId(), request.question());
        String answer = skillExecutor.execute("answer-generator", request.question(), knowledge);
        boolean escalationRequired = request.question().contains("人工") || request.question().contains("投诉");

        WorkflowStatus workflowStatus = escalationRequired ? WorkflowStatus.WAITING_HUMAN : WorkflowStatus.COMPLETED;
        TaskStatus taskStatus = escalationRequired ? TaskStatus.WAITING_HUMAN : TaskStatus.COMPLETED;

        TaskInstanceDto task = new TaskInstanceDto(
            taskId,
            request.scenarioId(),
            request.question(),
            request.requester(),
            taskStatus,
            Instant.now(),
            workflowId
        );
        tasks.add(task);

        List<NodeExecutionDto> nodes = List.of(
            node(workflowId, "question-received", "问题接收", NodeStatus.COMPLETED, request.question()),
            node(workflowId, "knowledge-retrieval", "知识检索", NodeStatus.COMPLETED, String.join("\n", knowledge)),
            node(workflowId, "answer-generation", "回答生成", NodeStatus.COMPLETED, answer),
            node(workflowId, "escalation-decision", "升级判定", escalationRequired ? NodeStatus.WAITING_HUMAN : NodeStatus.COMPLETED,
                escalationRequired ? "命中升级条件，等待人工接管" : "无需升级，流程结束")
        );

        WorkflowInstanceDto workflow = new WorkflowInstanceDto(
            workflowId,
            taskId,
            workflowStatus,
            answer,
            escalationRequired,
            nodes,
            escalationRequired ? List.of(new HumanInterventionDto(nextId("human"), workflowId, "WAIT_CONFIRM", "system", "等待人工确认", Instant.now())) : List.of()
        );
        workflows.add(workflow);

        return task;
    }

    public WorkflowInstanceDto getWorkflow(String workflowId) {
        return workflows.stream().filter(item -> item.id().equals(workflowId)).findFirst().orElseThrow();
    }

    public WorkflowInstanceDto handleHumanAction(String workflowId, HumanActionRequest request) {
        WorkflowInstanceDto existing = getWorkflow(workflowId);
        HumanInterventionDto intervention = new HumanInterventionDto(
            nextId("human"),
            workflowId,
            request.action(),
            "u-demo-operator",
            request.comment(),
            Instant.now()
        );
        WorkflowInstanceDto updated = new WorkflowInstanceDto(
            existing.id(),
            existing.taskId(),
            "TERMINATE".equalsIgnoreCase(request.action()) ? WorkflowStatus.CANCELLED : WorkflowStatus.COMPLETED,
            existing.summary(),
            false,
            completeNodes(existing.nodes(), request),
            append(existing.interventions(), intervention)
        );
        workflows.remove(existing);
        workflows.add(updated);

        TaskInstanceDto task = tasks.stream().filter(item -> item.workflowInstanceId().equals(workflowId)).findFirst().orElseThrow();
        TaskInstanceDto updatedTask = new TaskInstanceDto(
            task.id(),
            task.scenarioId(),
            task.question(),
            task.requester(),
            "TERMINATE".equalsIgnoreCase(request.action()) ? TaskStatus.CANCELLED : TaskStatus.COMPLETED,
            task.createdAt(),
            task.workflowInstanceId()
        );
        tasks.remove(task);
        tasks.add(updatedTask);
        return updated;
    }

    private void seed() {
        launchTask(new TaskLaunchRequest("scenario-knowledge-escalation", "怎么重置密码？", "业务用户A"));
        launchTask(new TaskLaunchRequest("scenario-knowledge-escalation", "这是一个客户投诉，需要人工处理", "业务用户B"));
    }

    private static NodeExecutionDto node(String workflowId, String key, String name, NodeStatus status, String detail) {
        return new NodeExecutionDto(nextId("node"), workflowId, key, name, status, detail, Instant.now());
    }

    private static List<NodeExecutionDto> completeNodes(List<NodeExecutionDto> nodes, HumanActionRequest request) {
        return nodes.stream()
            .map(node -> node.status() == NodeStatus.WAITING_HUMAN
                ? new NodeExecutionDto(node.id(), node.workflowInstanceId(), node.nodeKey(), node.nodeName(), NodeStatus.COMPLETED, request.comment(), Instant.now())
                : node)
            .toList();
    }

    private static List<HumanInterventionDto> append(List<HumanInterventionDto> items, HumanInterventionDto item) {
        List<HumanInterventionDto> updated = new ArrayList<>(items);
        updated.add(item);
        return updated;
    }

    private static String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
