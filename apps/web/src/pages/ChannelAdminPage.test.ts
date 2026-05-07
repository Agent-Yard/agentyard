import { describe, expect, it } from 'vitest';
import {
  accountOptionsForChannelProvider,
  buildProviderJobWritePayload,
  buildTemplateBindingWritePayload,
  channelProviderOptions,
  createJobFormState,
  createProfileFormForDefinition,
  currentProfileAccountHardBlock,
  defaultConfigForProvider,
  parseJsonObject,
} from './channelAdminPage';
import type {
  ChannelProfile,
  ChannelProviderDefinition,
  ChannelProviderJobConfig,
  IntegrationAccount,
} from '../types';

const providerDefinition: ChannelProviderDefinition = {
  providerType: 'provider.alpha',
  title: 'Alpha Provider',
  description: 'Alpha channel provider',
  definitionDigest: 'sha256:alpha',
  accountConfigSchema: { type: 'object' },
  accountConfigUiSchema: [],
  credentialCapability: {
    supported: false,
    mode: null,
    credentialSchema: null,
    credentialUiSchema: [],
    supportsValidate: false,
  },
  configSchema: {
    type: 'object',
    properties: {
      channelId: { type: 'string' },
    },
  },
  configUiSchema: [],
  defaultConfig: {
    channelId: 'general',
    password: 'do-not-copy',
    nested: {
      apiKey: 'do-not-copy',
      mode: 'pull',
    },
    [['external', 'Secret', 'Ref'].join('')]: 'do-not-copy',
  },
  jobDefinitions: [{
    jobType: 'PULL_MESSAGES',
    title: 'Pull messages',
    description: 'Pull missed messages',
    jobConfigSchema: { type: 'object' },
    jobConfigUiSchema: [],
    defaultSchedule: {
      scheduleType: 'CRON',
      cronExpression: '0 * * * *',
      timezone: 'Asia/Shanghai',
      jobTimeoutSeconds: 90,
      jobConfig: { cursor: 'latest' },
    },
    defaultEnabled: true,
    defaultJobTimeoutSeconds: 60,
  }],
};

function account(overrides: Partial<IntegrationAccount>): IntegrationAccount {
  return {
    id: 'account-1',
    subjectType: 'CHANNEL_PROVIDER',
    subjectId: 'provider.alpha',
    name: 'Alpha account',
    status: 'ENABLED',
    config: {},
    hasExternalSecretRef: false,
    credentialConfigured: false,
    credentialStatus: 'ACTIVE',
    createdAt: '2026-04-01T00:00:00Z',
    updatedAt: '2026-04-01T00:00:00Z',
    ...overrides,
  };
}

function profile(overrides: Partial<ChannelProfile>): ChannelProfile {
  return {
    id: 'channel-profile-1',
    providerType: 'provider.alpha',
    displayName: 'Alpha profile',
    status: 'ACTIVE',
    inboundEnabled: true,
    config: {},
    assistantBinding: null,
    accountId: 'account-1',
    hasExternalSecretRef: false,
    revision: 3,
    integrationAccount: null,
    createdAt: '2026-04-01T00:00:00Z',
    updatedAt: '2026-04-01T00:00:00Z',
    ...overrides,
  };
}

function existingJob(overrides: Partial<ChannelProviderJobConfig>): ChannelProviderJobConfig {
  return {
    jobId: 'job-1',
    jobType: 'PULL_MESSAGES',
    status: 'DISABLED',
    scheduleConfig: {
      scheduleType: 'INTERVAL',
      intervalSeconds: 120,
      cronExpression: null,
      timezone: 'UTC',
      jobTimeoutSeconds: 45,
      jobConfig: { cursor: 'saved' },
    },
    nextRunAt: null,
    lastRunAt: null,
    lastSuccessAt: null,
    lastError: null,
    failureCount: 0,
    revision: 7,
    createdAt: '2026-04-01T00:00:00Z',
    updatedAt: '2026-04-01T00:00:00Z',
    ...overrides,
  };
}

describe('ChannelAdminPage helpers', () => {
  it('builds provider choices and sanitized default profile config from definition endpoints', () => {
    expect(channelProviderOptions([providerDefinition])).toEqual([{
      label: 'Alpha Provider (provider.alpha)',
      value: 'provider.alpha',
      description: 'Alpha channel provider',
    }]);

    expect(defaultConfigForProvider(providerDefinition)).toEqual({
      channelId: 'general',
      nested: { mode: 'pull' },
    });
    expect(createProfileFormForDefinition(providerDefinition)).toMatchObject({
      providerType: 'provider.alpha',
      displayName: 'Alpha Provider',
      status: 'INACTIVE',
      config: {
        channelId: 'general',
        nested: { mode: 'pull' },
      },
    });
  });

  it('filters channel provider accounts and labels credential risk statuses', () => {
    const options = accountOptionsForChannelProvider([
      account({ id: 'enabled', name: 'Enabled account' }),
      account({ id: 'risk', name: 'Risk account', credentialStatus: 'ROTATION_REQUIRED' }),
      account({ id: 'wrong-subject', subjectType: 'TOOL_CONNECTOR' }),
      account({ id: 'wrong-provider', subjectId: 'provider.beta' }),
      account({ id: 'disabled', status: 'DISABLED' }),
      account({ id: 'revoked', credentialStatus: 'REVOKED' }),
      account({ id: 'revoke-failed', credentialStatus: 'REVOKE_FAILED' }),
    ], 'provider.alpha');

    expect(options).toEqual([
      { label: 'Enabled account', value: 'enabled', credentialStatus: 'ACTIVE' },
      { label: 'Risk account (ROTATION_REQUIRED)', value: 'risk', credentialStatus: 'ROTATION_REQUIRED' },
    ]);
  });

  it('keeps a hard-blocked current account blocking until the selection changes', () => {
    const blockedProfile = profile({
      integrationAccount: {
        id: 'account-1',
        name: 'Blocked account',
        status: 'ENABLED',
        credentialStatus: 'REVOKED',
        credentialConfigured: true,
        availabilityHardBlock: 'CREDENTIAL_REVOKED',
        risks: [],
      },
    });

    expect(currentProfileAccountHardBlock(blockedProfile, 'account-1')).toBe('CREDENTIAL_REVOKED');
    expect(currentProfileAccountHardBlock(blockedProfile, 'account-2')).toBeNull();
    expect(currentProfileAccountHardBlock(blockedProfile, null)).toBeNull();
  });

  it('constructs provider job write payloads from schedule fields and saved revision', () => {
    const form = createJobFormState(providerDefinition.jobDefinitions[0], existingJob({}));
    form.enabled = true;
    form.scheduleType = 'CRON';
    form.intervalSeconds = 120;
    form.cronExpression = '*/5 * * * *';
    form.timezone = 'Asia/Shanghai';
    form.jobTimeoutSeconds = 75;
    form.jobConfig = { cursor: 'next' };

    expect(buildProviderJobWritePayload(form, existingJob({}))).toEqual({
      scheduleConfig: {
        enabled: true,
        scheduleType: 'CRON',
        intervalSeconds: null,
        cronExpression: '*/5 * * * *',
        timezone: 'Asia/Shanghai',
        jobTimeoutSeconds: 75,
        jobConfig: { cursor: 'next' },
      },
      expectedRevision: 7,
    });
  });

  it('validates template binding variable schema as a JSON object before building payload', () => {
    expect(parseJsonObject('{"type":"object"}')).toEqual({
      valid: true,
      value: { type: 'object' },
      error: null,
    });
    expect(parseJsonObject('[]').valid).toBe(false);
    expect(parseJsonObject('{').valid).toBe(false);

    expect(buildTemplateBindingWritePayload({
      externalTemplateId: ' tpl_123 ',
      externalTemplateVersion: ' published ',
      variableSchemaJson: '{"type":"object"}',
      displayName: ' Order card ',
      externalEditUrl: ' https://provider.example/templates/tpl_123 ',
      enabled: true,
      expectedRevision: 2,
    })).toEqual({
      externalTemplateId: 'tpl_123',
      externalTemplateVersion: 'published',
      variableSchema: { type: 'object' },
      displayName: 'Order card',
      externalEditUrl: 'https://provider.example/templates/tpl_123',
      enabled: true,
      expectedRevision: 2,
    });
  });
});
