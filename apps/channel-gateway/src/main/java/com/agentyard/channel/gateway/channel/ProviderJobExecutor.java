package com.agentyard.channel.gateway.channel;

interface ProviderJobExecutor {
    ProviderJobExecutionResult run(ProviderJobClaim claim) throws Exception;
}
