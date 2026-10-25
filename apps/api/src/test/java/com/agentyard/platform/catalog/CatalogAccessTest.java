package com.agentyard.platform.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.agentyard.platform.auth.AuthModels.*;
import com.agentyard.platform.task.CollaborationService;
import com.agentyard.contracts.task.TaskContracts.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class CatalogAccessTest {
    @Test void personalConfigurationCannotBeReadOrChangedByOtherDevelopers() {
        var user = user(Role.DEVELOPER);
        var access = new CatalogAccess(() -> user, mock(CollaborationService.class), new CatalogService());
        var own = new CatalogDtos.RoleTemplateDto("own", "Own", "", "PERSON", user.id(), null, null, List.of());
        var other = new CatalogDtos.RoleTemplateDto("other", "Other", "", "PERSON", "someone-else", null, null, List.of());
        assertEquals(List.of(own), access.visible(List.of(own, other)));
        access.write(own);
        assertThrows(AccessDeniedException.class, () -> access.read(other));
        assertThrows(AccessDeniedException.class, () -> access.write(other));
        assertFalse(access.isPlatformAdmin());
    }
    @Test void organizationMembershipDoesNotImplyConfigurationManagement() {
        var collaboration = mock(CollaborationService.class);
        when(collaboration.organization("org")).thenReturn(new OrganizationDetail(null, List.of(), Set.of(Permission.VIEW)));
        var access = new CatalogAccess(() -> user(Role.DEVELOPER), collaboration, new CatalogService());
        var template = new CatalogDtos.RoleTemplateDto("org-role", "Role", "", "ORGANIZATION", "org", null, null, List.of());
        access.read(template);
        assertThrows(AccessDeniedException.class, () -> access.write(template));
    }
    @Test void platformAdministratorCanManageDeploymentGrants() {
        var access = new CatalogAccess(() -> user(Role.PLATFORM_ADMIN), mock(CollaborationService.class), new CatalogService());
        assertTrue(access.isPlatformAdmin());
        access.write(new CatalogDtos.RoleTemplateDto("role", "Role", "", "PERSON", "another", null, null, List.of()));
    }
    private PlatformUser user(Role role) {
        return new PlatformUser("operator", "operator", "Operator", null, AuthSource.LOCAL_BOOTSTRAP, null, null, UserStatus.ACTIVE,
            Instant.now(), Instant.now(), null, List.of(role));
    }
}
