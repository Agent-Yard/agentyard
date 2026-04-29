package com.lynxus.channel.gateway.channel;

interface ProviderJobExecutor {
    ProviderJobExecutionResult run(ProviderJobClaim claim) throws Exception;
}
