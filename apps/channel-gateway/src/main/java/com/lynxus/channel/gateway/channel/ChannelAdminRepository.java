package com.lynxus.channel.gateway.channel;

import com.lynxus.channel.gateway.jooqsupport.JooqJsonbSupport;
import com.lynxus.contracts.channel.ChannelContracts.ChannelGatewayProfile;
import com.lynxus.contracts.channel.ChannelContracts.ChannelConversationBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelInboundEvent;
import com.lynxus.contracts.channel.ChannelContracts.ChannelOutboundDelivery;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobConfig;
import com.lynxus.contracts.channel.ChannelContracts.ChannelProviderJobRun;
import com.lynxus.contracts.channel.ChannelContracts.ChannelTemplateBinding;
import com.lynxus.contracts.channel.ChannelContracts.ChannelTemplateBindingKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ChannelAdminRepository {
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    private final ChannelStore store;

    public ChannelAdminRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
        this.store = new ChannelStore(dsl, new JooqJsonbSupport(objectMapper));
    }

    public <T> T transactionResult(Function<ChannelAdminRepository, T> action) {
        return dsl.transactionResult(configuration -> action.apply(new ChannelAdminRepository(DSL.using(configuration), objectMapper)));
    }

    public List<ChannelGatewayProfile> listProfiles() {
        return store.listProfiles();
    }

    public Optional<ChannelGatewayProfile> findProfile(String channelProfileId) {
        return store.findProfile(channelProfileId);
    }

    Optional<ChannelOutboundProfileSnapshot> findOutboundProfileSnapshot(String channelProfileId) {
        return store.findOutboundProfileSnapshot(channelProfileId);
    }

    public List<ChannelGatewayProfile> listProfilesByProvider(String providerType) {
        return store.listProfilesByProvider(providerType);
    }

    public void createProfile(ChannelGatewayProfile profile, String externalSecretRef) {
        store.createProfile(profile, externalSecretRef);
    }

    public boolean updateProfile(ChannelGatewayProfile profile, long expectedRevision, String externalSecretRef) {
        return store.updateProfile(profile, expectedRevision, externalSecretRef);
    }

    public boolean disableProfile(String channelProfileId, long expectedRevision, long nextRevision, Instant updatedAt) {
        return store.disableProfile(channelProfileId, expectedRevision, nextRevision, updatedAt);
    }

    public List<ChannelConversationBinding> listBindings(String channelProfileId) {
        return store.listBindings(channelProfileId);
    }

    public Optional<ChannelConversationBinding> findBindingByProfileAndExternalConversation(
        String channelProfileId,
        String externalConversationId
    ) {
        return store.findBindingByProfileAndExternalConversation(channelProfileId, externalConversationId);
    }

    public Optional<ChannelConversationBinding> findBindingBySessionId(String sessionId) {
        return store.findBindingBySessionId(sessionId);
    }

    public void saveBinding(ChannelConversationBinding binding) {
        store.saveBinding(binding);
    }

    public void saveBindingForConversation(ChannelConversationBinding binding) {
        store.saveBindingForConversation(binding);
    }

    public List<ChannelInboundEvent> listInboundEvents(String channelProfileId) {
        return store.listInboundEvents(channelProfileId);
    }

    public Optional<ChannelInboundEvent> findInboundEventByDedupKey(String dedupKey) {
        return store.findInboundEventByDedupKey(dedupKey);
    }

    public void saveInboundEvent(ChannelInboundEvent event) {
        store.saveInboundEvent(event);
    }

    public boolean saveInboundEventIfAbsent(ChannelInboundEvent event) {
        return store.saveInboundEventIfAbsent(event);
    }

    public List<ChannelOutboundDelivery> listOutboundDeliveries(String channelProfileId) {
        return store.listOutboundDeliveries(channelProfileId);
    }

    public Optional<ChannelOutboundDelivery> findOutboundDeliveryByIdempotencyKey(String idempotencyKey) {
        return store.findOutboundDeliveryByIdempotencyKey(idempotencyKey);
    }

    public void saveOutboundDelivery(ChannelOutboundDelivery delivery) {
        store.saveOutboundDelivery(delivery);
    }

    public List<ChannelTemplateBinding> listTemplateBindings(String channelProfileId) {
        return store.listTemplateBindings(channelProfileId);
    }

    public Optional<ChannelTemplateBinding> findTemplateBinding(ChannelTemplateBindingKey key) {
        return store.findTemplateBinding(key);
    }

    public void createTemplateBinding(ChannelTemplateBinding binding) {
        store.createTemplateBinding(binding);
    }

    public boolean updateTemplateBinding(ChannelTemplateBinding binding, long expectedRevision) {
        return store.updateTemplateBinding(binding, expectedRevision);
    }

    public boolean disableTemplateBinding(String bindingId, long expectedRevision, long nextRevision, Instant updatedAt) {
        return store.disableTemplateBinding(bindingId, expectedRevision, nextRevision, updatedAt);
    }

    public List<ChannelProviderJobConfig> listJobs(String channelProfileId, List<String> jobTypes) {
        return store.listJobs(channelProfileId, jobTypes);
    }

    public Optional<ChannelProviderJobConfig> findJob(String channelProfileId, String jobType) {
        return store.findJob(channelProfileId, jobType);
    }

    public Optional<ChannelProviderJobConfig> findJobById(String jobId) {
        return store.findJobById(jobId);
    }

    public void createJob(String channelProfileId, ChannelProviderJobConfig job) {
        store.createJob(channelProfileId, job);
    }

    public boolean updateJob(ChannelProviderJobConfig job, long expectedRevision) {
        return store.updateJob(job, expectedRevision);
    }

    public boolean disableJob(String jobId, long expectedRevision, long nextRevision, Instant updatedAt) {
        return store.disableJob(jobId, expectedRevision, nextRevision, updatedAt);
    }

    public List<ChannelProviderJobRun> listJobRuns(String jobId) {
        return store.listJobRuns(jobId);
    }

    public List<String> listDueActiveJobIds(Instant now, int limit) {
        return store.listDueActiveJobIds(now, limit);
    }

    public Optional<ProviderJobClaim> claimJob(
        String jobId,
        String runId,
        String idempotencyKey,
        boolean manual,
        Instant now
    ) {
        return store.claimJob(jobId, runId, idempotencyKey, manual, now);
    }

    public boolean completeRunSucceeded(String jobId, String runId, ProviderJobExecutionResult result, Instant now) {
        return store.completeRunSucceeded(jobId, runId, result, now);
    }

    public boolean completeRunFailed(String jobId, String runId, String error, Instant now) {
        return store.completeRunFailed(jobId, runId, error, now);
    }

    public boolean completeRunTimedOut(String jobId, String runId, String error, Instant now) {
        return store.completeRunTimedOut(jobId, runId, error, now);
    }

    public List<ProviderJobRunningRun> listRunningRuns() {
        return store.listRunningRuns();
    }

    public boolean recoverTimedOutRun(String jobId, String runId, String error, Instant now) {
        return store.recoverTimedOutRun(jobId, runId, error, now);
    }
}
