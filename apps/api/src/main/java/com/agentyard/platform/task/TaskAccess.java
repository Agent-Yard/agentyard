package com.agentyard.platform.task;

import static com.agentyard.contracts.task.TaskContracts.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class TaskAccess {
    private TaskAccess() {}
    static Set<String> roles(List<Participant> participants, String actor) {
        return participants.stream().filter(p -> p.actorId().equals(actor)).findFirst()
            .map(p -> Set.copyOf(p.roles())).orElse(Set.of());
    }
    static Set<Permission> permissions(List<RoleDefinition> definitions, Set<String> roles) {
        Set<Permission> result = EnumSet.noneOf(Permission.class);
        definitions.stream().filter(r -> roles.contains(r.key())).forEach(r -> result.addAll(r.permissions()));
        return result;
    }
    static void require(Set<Permission> permissions, Permission permission) {
        if (!permissions.contains(permission)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "missing task permission: " + permission);
    }
    static boolean visible(ContextEntry entry, String actor, Set<String> roles) {
        return entry.authorId().equals(actor) || entry.visibleToRoles().isEmpty() || !Collections.disjoint(entry.visibleToRoles(), roles);
    }
    static List<RoleDefinition> validateRoles(List<RoleDefinition> roles) {
        if (roles == null) roles = defaultRoles();
        Set<String> keys = new HashSet<>();
        for (var role : roles) {
            if (role.key() == null || !role.key().matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}") || !keys.add(role.key())) bad("role keys must be unique identifiers");
            text(role.name(), "role name");
            if (!role.permissions().contains(Permission.VIEW)) bad("every role requires VIEW");
        }
        if (roles.stream().noneMatch(r -> r.permissions().contains(Permission.MANAGE))) bad("at least one management role is required");
        return List.copyOf(roles);
    }
    static void validateAssignments(List<RoleDefinition> definitions, Collection<String> roles) {
        Set<String> keys = new HashSet<>(); definitions.forEach(r -> keys.add(r.key()));
        if (!keys.containsAll(roles)) bad("unknown role");
    }
    static void retainManager(List<RoleDefinition> definitions, List<Participant> participants) {
        if (participants.stream().noneMatch(p -> permissions(definitions, Set.copyOf(p.roles())).contains(Permission.MANAGE))) bad("at least one responsible manager must remain");
    }
    static String managerRole(List<RoleDefinition> roles) {
        return roles.stream().filter(r -> r.permissions().contains(Permission.MANAGE)).findFirst().orElseThrow().key();
    }
    static String text(String value, String field) {
        if (value == null || value.isBlank()) bad(field + " is required");
        if (value.length() > 100000) bad(field + " is too large");
        return value.trim();
    }
    static void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    static void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT, message); }
    static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "collaboration object not found"); }
}
