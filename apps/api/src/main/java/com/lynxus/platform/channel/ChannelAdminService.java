package com.lynxus.platform.channel;

import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileAccountSnapshot;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProfileIntegrationAccountSummary;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundFrameCheckpoint;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobConfig;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobConfigWriteRequest;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobRun;
import com.lynxus.contracts.channel.ChannelContracts.ChannelTemplateBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelTemplateBindingWriteRequest;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelProfileInternalRequest;
import com.lynxus.contracts.channel.ChannelContracts.CreateChannelProfileRequest;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelProfileInternalRequest;
import com.lynxus.contracts.channel.ChannelContracts.UpdateChannelProfileRequest;
import com.lynxus.contracts.session.SessionContracts.SessionMessageBlockType;
import com.lynxus.platform.extension.ExtensionDefinitionService;
import com.lynxus.platform.extension.ExtensionDefinitionDtos.ChannelProviderDefinition;
import com.lynxus.platform.integration.IntegrationAccountService;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountAvailabilityDecision;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountDto;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountRuntimeSnapshot;
import com.lynxus.platform.integration.IntegrationDtos.IntegrationAccountSubjectType;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ChannelAdminService {
    private final ChannelGatewayClient channelGatewayClient;
    private final IntegrationAccountService integrationAccountService;
    private final ExtensionDefinitionService extensionDefinitionService;
    private final ChannelBindingSnapshotRefreshCoordinator bindingSnapshotRefreshCoordinator;

    @Autowired
    public ChannelAdminService(
        ChannelGatewayClient channelGatewayClient,
        IntegrationAccountService integrationAccountService,
        ExtensionDefinitionService extensionDefinitionService,
        ChannelBindingSnapshotRefreshCoordinator bindingSnapshotRefreshCoordinator
    ) {
        this.channelGatewayClient = channelGatewayClient;
        this.integrationAccountService = integrationAccountService;
        this.extensionDefinitionService = extensionDefinitionService;
        this.bindingSnapshotRefreshCoordinator = bindingSnapshotRefreshCoordinator;
    }

    ChannelAdminService(ChannelGatewayClient channelGatewayClient, IntegrationAccountService integrationAccountService) {
        this(channelGatewayClient, integrationAccountService, null, null);
    }

    public List<ChannelProfile> listProfiles() {
        return channelGatewayClient.listProfiles().stream().map(this::toWebProfile).toList();
    }

    public ChannelProfile createProfile(CreateChannelProfileRequest request) {
        CreateChannelProfileInternalRequest internalRequest = new CreateChannelProfileInternalRequest(
            request.providerType(),
            request.displayName(),
            request.status(),
            request.inboundEnabled(),
            request.config(),
            request.assistantBinding(),
            materializeAccountSnapshot(request.integrationAccountId(), request.providerType())
        );
        ChannelProfile profile = toWebProfile(channelGatewayClient.createProfile(internalRequest));
        requestBindingSnapshotRefresh(profile.id(), "PROFILE_CREATED");
        return profile;
    }

    public ChannelProfile getProfile(String channelProfileId) {
        return toWebProfile(channelGatewayClient.getProfile(channelProfileId));
    }

    public ChannelProfile updateProfile(String channelProfileId, UpdateChannelProfileRequest request) {
        UpdateChannelProfileInternalRequest internalRequest = new UpdateChannelProfileInternalRequest(
            request.providerType(),
            request.displayName(),
            request.status(),
            request.inboundEnabled(),
            request.config(),
            request.assistantBinding(),
            materializeAccountSnapshot(request.integrationAccountId(), request.providerType()),
            request.expectedRevision()
        );
        ChannelProfile profile = toWebProfile(channelGatewayClient.updateProfile(channelProfileId, internalRequest));
        requestBindingSnapshotRefresh(profile.id(), "PROFILE_UPDATED");
        return profile;
    }

    public ChannelProfile deleteProfile(String channelProfileId, long expectedRevision) {
        ChannelProfile profile = toWebProfile(channelGatewayClient.deleteProfile(channelProfileId, expectedRevision));
        requestBindingSnapshotRefresh(profile.id(), "PROFILE_DELETED");
        return profile;
    }

    public List<ChannelConversationBinding> listBindings(String channelProfileId) {
        return channelGatewayClient.listBindings(channelProfileId);
    }

    public List<ChannelInboundEvent> listInboundEvents(String channelProfileId) {
        return channelGatewayClient.listInboundEvents(channelProfileId);
    }

    public List<ChannelOutboundFrameCheckpoint> listOutboundFinalCheckpoints(String channelProfileId) {
        return channelGatewayClient.listOutboundFinalCheckpoints(channelProfileId);
    }

    public List<ChannelTemplateBinding> listTemplateBindings(String channelProfileId) {
        return channelGatewayClient.listTemplateBindings(channelProfileId);
    }

    public ChannelTemplateBinding upsertTemplateBinding(
        String channelProfileId,
        String assistantId,
        String messageType,
        String messageSubtype,
        String messageVersion,
        ChannelTemplateBindingWriteRequest request
    ) {
        validateTemplateBindingTuple(assistantId, messageType, messageSubtype, messageVersion);
        return channelGatewayClient.upsertTemplateBinding(
            channelProfileId,
            assistantId,
            messageType,
            messageSubtype,
            messageVersion,
            request
        );
    }

    public ChannelTemplateBinding deleteTemplateBinding(
        String channelProfileId,
        String assistantId,
        String messageType,
        String messageSubtype,
        String messageVersion,
        long expectedRevision
    ) {
        validateTemplateBindingTuple(assistantId, messageType, messageSubtype, messageVersion);
        return channelGatewayClient.deleteTemplateBinding(
            channelProfileId,
            assistantId,
            messageType,
            messageSubtype,
            messageVersion,
            expectedRevision
        );
    }

    public List<ChannelProviderJobConfig> listJobs(String channelProfileId) {
        return channelGatewayClient.listJobs(channelProfileId);
    }

    public ChannelProviderJobConfig upsertJob(
        String channelProfileId,
        String jobType,
        ChannelProviderJobConfigWriteRequest request
    ) {
        requireJobDefinition(channelProfileId, jobType);
        return channelGatewayClient.upsertJob(channelProfileId, jobType, request);
    }

    public ChannelProviderJobConfig deleteJob(String channelProfileId, String jobType, long expectedRevision) {
        requireJobDefinition(channelProfileId, jobType);
        return channelGatewayClient.deleteJob(channelProfileId, jobType, expectedRevision);
    }

    public List<ChannelProviderJobRun> listJobRuns(String channelProfileId, String jobType) {
        requireJobDefinition(channelProfileId, jobType);
        return channelGatewayClient.listJobRuns(channelProfileId, jobType);
    }

    public ChannelProviderJobRun runJob(String channelProfileId, String jobType) {
        requireJobDefinition(channelProfileId, jobType);
        return channelGatewayClient.runJob(channelProfileId, jobType);
    }

    private ChannelProfileAccountSnapshot materializeAccountSnapshot(String integrationAccountId, String providerType) {
        if (integrationAccountId == null || integrationAccountId.isBlank()) {
            return null;
        }
        IntegrationAccountRuntimeSnapshot snapshot = integrationAccountService.requireRuntimeAccountSnapshot(
            integrationAccountId,
            IntegrationAccountSubjectType.CHANNEL_PROVIDER,
            providerType
        );
        return new ChannelProfileAccountSnapshot(snapshot.accountId(), snapshot.externalSecretRef());
    }

    private ChannelProfile toWebProfile(ChannelGatewayProfile profile) {
        return new ChannelProfile(
            profile.id(),
            profile.providerType(),
            profile.displayName(),
            profile.status(),
            profile.inboundEnabled(),
            profile.config(),
            profile.assistantBinding(),
            profile.accountId(),
            profile.hasExternalSecretRef(),
            profile.revision(),
            accountSummary(profile),
            profile.createdAt(),
            profile.updatedAt()
        );
    }

    private ChannelProfileIntegrationAccountSummary accountSummary(ChannelGatewayProfile profile) {
        if (profile.accountId() == null || profile.accountId().isBlank()) {
            return null;
        }
        try {
            IntegrationAccountDto account = integrationAccountService.getAccount(profile.accountId());
            IntegrationAccountAvailabilityDecision availability = integrationAccountService.evaluateAccountAvailability(
                profile.accountId(),
                IntegrationAccountSubjectType.CHANNEL_PROVIDER,
                profile.providerType()
            );
            return new ChannelProfileIntegrationAccountSummary(
                account.id(),
                account.name(),
                account.status().name(),
                account.credentialStatus().name(),
                account.credentialConfigured(),
                availability.hardBlock() == null ? null : availability.hardBlock().name(),
                availability.risks().stream().map(Enum::name).toList()
            );
        } catch (NoSuchElementException ignored) {
            return null;
        }
    }

    private void requireJobDefinition(String channelProfileId, String jobType) {
        if (extensionDefinitionService == null) {
            return;
        }
        ChannelGatewayProfile profile = channelGatewayClient.getProfile(channelProfileId);
        ChannelProviderDefinition providerDefinition = extensionDefinitionService.channelProviders().stream()
            .filter(definition -> definition.providerType().equals(profile.providerType()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("unknown channel provider: " + profile.providerType()));
        boolean found = providerDefinition.jobDefinitions().stream()
            .anyMatch(definition -> definition.jobType().equals(jobType));
        if (!found) {
            throw new IllegalArgumentException("unknown channel provider jobType: " + jobType);
        }
    }

    private void requestBindingSnapshotRefresh(String channelProfileId, String reason) {
        if (bindingSnapshotRefreshCoordinator != null) {
            bindingSnapshotRefreshCoordinator.requestProfileRefresh(channelProfileId, reason);
        }
    }

    private static void validateTemplateBindingTuple(
        String assistantId,
        String messageType,
        String messageSubtype,
        String messageVersion
    ) {
        requireText(assistantId, "templateBinding.assistantId");
        requireText(messageSubtype, "templateBinding.messageSubtype");
        requireText(messageVersion, "templateBinding.messageVersion");
        String normalizedMessageType = requireText(messageType, "templateBinding.messageType");
        try {
            SessionMessageBlockType.valueOf(normalizedMessageType);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("unknown session message block type: " + messageType);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
