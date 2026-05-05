package com.lynxus.channel.gateway.connector.feishu;

import com.lynxus.channel.gateway.channel.ChannelAdminRepository;
import com.lynxus.channel.gateway.channel.ChannelInboundSessionDispatchObserver;
import com.lynxus.channel.gateway.channel.ChannelInboundSessionDispatcher.ChannelInboundSessionDispatchResult;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.NormalizedChannelInboundEventResult;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
final class FeishuTypingReactionService implements ChannelInboundSessionDispatchObserver, FeishuTypingReactionLifecycle {
    static final String TYPING_EMOJI_TYPE = "Typing";

    private static final Logger log = LoggerFactory.getLogger(FeishuTypingReactionService.class);

    private final ChannelAdminRepository repository;
    private final FeishuTypingReactionStore reactionStore;
    private final FeishuCredentialProvider credentialProvider;
    private final FeishuMessageReactionClient reactionClient;

    FeishuTypingReactionService(
        ChannelAdminRepository repository,
        FeishuTypingReactionStore reactionStore,
        FeishuCredentialProvider credentialProvider,
        FeishuMessageReactionClient reactionClient
    ) {
        this.repository = repository;
        this.reactionStore = reactionStore;
        this.credentialProvider = credentialProvider;
        this.reactionClient = reactionClient;
    }

    void beginInboundTypingReaction(NormalizedChannelInboundEvent event) {
        if (!isFeishuMessage(event)) {
            return;
        }
        Optional<ChannelGatewayProfile> profile = repository.findProfile(event.channelProfileId());
        if (profile.isEmpty()) {
            return;
        }
        beginInboundTypingReaction(profile.orElseThrow(), event);
    }

    private void beginInboundTypingReaction(ChannelGatewayProfile profile, NormalizedChannelInboundEvent event) {
        if (!hasText(profile.accountId())) {
            return;
        }
        FeishuMessageReactionClient.FeishuAddReactionResult created;
        try {
            FeishuAppCredential credential = credentialProvider.resolve(profile.accountId(), profile.config());
            created = reactionClient.addReaction(new FeishuMessageReactionClient.FeishuAddReactionCommand(
                credential,
                event.externalMessageId(),
                TYPING_EMOJI_TYPE
            ));
        } catch (RuntimeException error) {
            log.warn(
                "failed to add Feishu typing reaction: channelProfileId={}, externalMessageId={}, dedupKey={}",
                event.channelProfileId(),
                event.externalMessageId(),
                event.dedupKey(),
                error
            );
            return;
        }

        String sessionId = repository.findBindingByProfileAndExternalConversation(
            event.channelProfileId(),
            event.externalConversationId()
        ).map(binding -> trimToNull(binding.sessionId())).orElse(null);
        FeishuTypingReactionState state = new FeishuTypingReactionState(
            profile.id(),
            event.externalConversationId(),
            event.externalMessageId(),
            event.dedupKey(),
            sessionId,
            created.reactionId()
        );
        try {
            boolean saved = reactionStore.saveIfAbsent(state);
            if (!saved) {
                deleteUntrackedReaction(profile, event.externalMessageId(), created.reactionId());
            }
        } catch (RuntimeException error) {
            deleteUntrackedReaction(profile, event.externalMessageId(), created.reactionId());
            log.warn(
                "failed to save Feishu typing reaction state in Redis: channelProfileId={}, externalMessageId={}, dedupKey={}",
                event.channelProfileId(),
                event.externalMessageId(),
                event.dedupKey(),
                error
            );
        }
    }

    @Override
    public void afterDispatchSucceeded(
        NormalizedChannelInboundEvent event,
        NormalizedChannelInboundEventResult ingestResult,
        ChannelInboundSessionDispatchResult dispatchResult
    ) {
        if (!isFeishuMessage(event) || dispatchResult == null || !hasText(dispatchResult.sessionId())) {
            return;
        }
        reactionStore.attachSessionByDedupKey(
            event.channelProfileId(),
            event.dedupKey(),
            dispatchResult.sessionId()
        );
    }

    @Override
    public void afterDispatchFailed(
        NormalizedChannelInboundEvent event,
        NormalizedChannelInboundEventResult ingestResult,
        RuntimeException error
    ) {
        if (!isFeishuMessage(event)) {
            return;
        }
        Optional<ChannelGatewayProfile> profile = repository.findProfile(event.channelProfileId());
        if (profile.isEmpty()) {
            return;
        }
        reactionStore.claimByDedupKey(
            event.channelProfileId(),
            event.dedupKey()
        ).ifPresent(state -> deleteClaimedReaction(profile.orElseThrow(), state));
    }

    @Override
    public void deleteTypingReactionOnFirstOutboundFrame(
        ChannelGatewayProfile profile,
        String sessionId,
        String externalConversationId
    ) {
        if (profile == null
            || !FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE.equals(profile.providerType())) {
            return;
        }
        Optional<FeishuTypingReactionState> state = Optional.empty();
        if (hasText(sessionId)) {
            state = reactionStore.claimBySession(
                profile.id(),
                sessionId
            );
        }
        if (state.isEmpty() && hasText(externalConversationId)) {
            state = reactionStore.claimByConversation(
                profile.id(),
                externalConversationId
            );
        }
        state.ifPresent(claimed -> deleteClaimedReaction(profile, claimed));
    }

    private void deleteClaimedReaction(ChannelGatewayProfile profile, FeishuTypingReactionState state) {
        try {
            FeishuAppCredential credential = credentialProvider.resolve(profile.accountId(), profile.config());
            reactionClient.deleteReaction(new FeishuMessageReactionClient.FeishuDeleteReactionCommand(
                credential,
                state.externalMessageId(),
                state.providerReactionId()
            ));
        } catch (RuntimeException error) {
            reactionStore.restore(state);
            log.warn(
                "failed to delete Feishu typing reaction: channelProfileId={}, externalMessageId={}, reactionId={}",
                state.channelProfileId(),
                state.externalMessageId(),
                state.providerReactionId(),
                error
            );
        }
    }

    private void deleteUntrackedReaction(ChannelGatewayProfile profile, String messageId, String reactionId) {
        try {
            FeishuAppCredential credential = credentialProvider.resolve(profile.accountId(), profile.config());
            reactionClient.deleteReaction(new FeishuMessageReactionClient.FeishuDeleteReactionCommand(
                credential,
                messageId,
                reactionId
            ));
        } catch (RuntimeException error) {
            log.warn(
                "failed to delete untracked Feishu typing reaction: channelProfileId={}, externalMessageId={}, reactionId={}",
                profile.id(),
                messageId,
                reactionId,
                error
            );
        }
    }

    private static boolean isFeishuMessage(NormalizedChannelInboundEvent event) {
        return event != null
            && FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE.equals(event.providerType())
            && hasText(event.channelProfileId())
            && hasText(event.externalConversationId())
            && hasText(event.externalMessageId())
            && hasText(event.dedupKey());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
