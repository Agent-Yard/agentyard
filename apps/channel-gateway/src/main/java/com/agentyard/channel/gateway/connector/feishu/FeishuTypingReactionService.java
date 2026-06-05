package com.agentyard.channel.gateway.connector.feishu;

import com.agentyard.channel.gateway.channel.ChannelAdminRepository;
import com.agentyard.channel.gateway.channel.ChannelInboundSessionDispatchObserver;
import com.agentyard.channel.gateway.channel.ChannelInboundTurnIngestResult;
import com.agentyard.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelInboundTurn;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelInboundTurnResult;
import com.agentyard.contracts.channel.ChannelContracts.NormalizedChannelTurnMessage;
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

    void beginInboundTypingReaction(NormalizedChannelInboundTurn turn) {
        if (!isFeishuTurn(turn)) {
            return;
        }
        Optional<ChannelGatewayProfile> profile = repository.findProfile(turn.channelProfileId());
        if (profile.isEmpty()) {
            return;
        }
        beginInboundTypingReaction(profile.orElseThrow(), turn);
    }

    private void beginInboundTypingReaction(ChannelGatewayProfile profile, NormalizedChannelInboundTurn turn) {
        NormalizedChannelTurnMessage firstMessage = firstMessage(turn);
        if (firstMessage == null || !hasText(profile.accountId())) {
            return;
        }
        FeishuMessageReactionClient.FeishuAddReactionResult created;
        try {
            FeishuAppCredential credential = credentialProvider.resolve(profile.accountId());
            created = reactionClient.addReaction(new FeishuMessageReactionClient.FeishuAddReactionCommand(
                credential,
                firstMessage.externalMessageId(),
                TYPING_EMOJI_TYPE
            ));
        } catch (RuntimeException error) {
            log.warn(
                "failed to add Feishu typing reaction: channelProfileId={}, externalMessageId={}, dedupKey={}",
                turn.channelProfileId(),
                firstMessage.externalMessageId(),
                turn.dedupKey(),
                error
            );
            return;
        }

        String sessionId = repository.findBindingByProfileAndExternalConversation(
            turn.channelProfileId(),
            turn.externalConversationId()
        ).map(binding -> trimToNull(binding.sessionId())).orElse(null);
        FeishuTypingReactionState state = new FeishuTypingReactionState(
            profile.id(),
            turn.externalConversationId(),
            firstMessage.externalMessageId(),
            turn.dedupKey(),
            sessionId,
            created.reactionId()
        );
        try {
            boolean saved = reactionStore.saveIfAbsent(state);
            if (!saved) {
                deleteUntrackedReaction(profile, firstMessage.externalMessageId(), created.reactionId());
            }
        } catch (RuntimeException error) {
            deleteUntrackedReaction(profile, firstMessage.externalMessageId(), created.reactionId());
            log.warn(
                "failed to save Feishu typing reaction state in Redis: channelProfileId={}, externalMessageId={}, dedupKey={}",
                turn.channelProfileId(),
                firstMessage.externalMessageId(),
                turn.dedupKey(),
                error
            );
        }
    }

    @Override
    public void afterDispatchSucceeded(
        NormalizedChannelInboundTurn turn,
        ChannelInboundTurnIngestResult ingestResult,
        NormalizedChannelInboundTurnResult dispatchResult
    ) {
        if (!isFeishuTurn(turn) || dispatchResult == null || !hasText(dispatchResult.sessionId())) {
            return;
        }
        reactionStore.attachSessionByDedupKey(
            turn.channelProfileId(),
            turn.dedupKey(),
            dispatchResult.sessionId()
        );
    }

    @Override
    public void afterDispatchFailed(
        NormalizedChannelInboundTurn turn,
        ChannelInboundTurnIngestResult ingestResult,
        RuntimeException error
    ) {
        if (!isFeishuTurn(turn)) {
            return;
        }
        Optional<ChannelGatewayProfile> profile = repository.findProfile(turn.channelProfileId());
        if (profile.isEmpty()) {
            return;
        }
        reactionStore.claimByDedupKey(
            turn.channelProfileId(),
            turn.dedupKey()
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
            FeishuAppCredential credential = credentialProvider.resolve(profile.accountId());
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
            FeishuAppCredential credential = credentialProvider.resolve(profile.accountId());
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

    private static boolean isFeishuTurn(NormalizedChannelInboundTurn turn) {
        NormalizedChannelTurnMessage firstMessage = firstMessage(turn);
        return turn != null
            && FeishuGatewayNativeChannelProviderAdapter.PROVIDER_TYPE.equals(turn.providerType())
            && hasText(turn.channelProfileId())
            && hasText(turn.externalConversationId())
            && firstMessage != null
            && hasText(firstMessage.externalMessageId())
            && hasText(turn.dedupKey());
    }

    private static NormalizedChannelTurnMessage firstMessage(NormalizedChannelInboundTurn turn) {
        if (turn == null || turn.messages().isEmpty()) {
            return null;
        }
        return turn.messages().getFirst();
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
