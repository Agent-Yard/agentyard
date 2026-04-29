import type {
  ChannelProfile,
  ChannelProviderDefinition,
  ChannelProviderJobConfig,
  ChannelProviderJobConfigWritePayload,
  ChannelProviderJobScheduleType,
  ChannelTemplateBindingWritePayload,
  IntegrationAccount,
} from '../types';
import type { JsonObject } from '../components/schemaDrivenForm';

const hardBlockedCredentialStatuses = new Set(['REVOKE_FAILED', 'REVOKED']);
const riskCredentialStatuses = new Set(['NOT_CONFIGURED', 'VALIDATION_FAILED', 'ROTATION_REQUIRED']);
const blockedSecretKey = ['external', 'Secret', 'Ref'].join('');
const secretLikeKeys = new Set([
  blockedSecretKey.toLowerCase(),
  'secret',
  'password',
  'apikey',
  'accesskey',
  'secretkey',
  'accesstoken',
  'refreshtoken',
  'privatekey',
  'webhooksigningsecret',
  'clientsecret',
]);

export interface ChannelProviderOption {
  label: string;
  value: string;
  description: string | null;
}

export interface ChannelProviderAccountOption {
  label: string;
  value: string;
  credentialStatus: IntegrationAccount['credentialStatus'];
}

export interface ChannelProfileFormState {
  providerType: string;
  displayName: string;
  status: ChannelProfile['status'];
  inboundEnabled: boolean;
  assistantId: string | null;
  scenarioId: string | null;
  integrationAccountId: string | null;
  config: JsonObject;
}

export interface ChannelProviderJobFormState {
  enabled: boolean;
  scheduleType: ChannelProviderJobScheduleType;
  intervalSeconds: number | null;
  cronExpression: string | null;
  timezone: string;
  jobTimeoutSeconds: number | null;
  jobConfig: JsonObject;
}

export interface TemplateBindingFormState {
  externalTemplateId: string;
  externalTemplateVersion: string | null;
  variableSchemaJson: string;
  displayName: string;
  externalEditUrl: string | null;
  enabled: boolean;
  expectedRevision?: number | null;
}

export interface JsonObjectParseResult {
  valid: boolean;
  value: JsonObject;
  error: string | null;
}

function normalizeKey(key: string): string {
  return key.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
}

function isPlainObject(value: unknown): value is JsonObject {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function cloneJsonObject(value: unknown): JsonObject {
  return isPlainObject(value) ? JSON.parse(JSON.stringify(value)) as JsonObject : {};
}

function stripSecretLikeKeys(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(stripSecretLikeKeys);
  }
  if (!isPlainObject(value)) {
    return value;
  }

  return Object.fromEntries(
    Object.entries(value)
      .filter(([key]) => !secretLikeKeys.has(normalizeKey(key)))
      .map(([key, child]) => [key, stripSecretLikeKeys(child)]),
  );
}

export function channelProviderOptions(definitions: ChannelProviderDefinition[]): ChannelProviderOption[] {
  return definitions.map((definition) => ({
    label: `${definition.title} (${definition.providerType})`,
    value: definition.providerType,
    description: definition.description,
  }));
}

export function defaultConfigForProvider(definition: ChannelProviderDefinition | null | undefined): JsonObject {
  return cloneJsonObject(stripSecretLikeKeys(definition?.defaultConfig ?? {}));
}

export function createProfileFormForDefinition(definition: ChannelProviderDefinition | null | undefined): ChannelProfileFormState {
  return {
    providerType: definition?.providerType ?? '',
    displayName: definition?.title ?? '',
    status: 'INACTIVE',
    inboundEnabled: false,
    assistantId: null,
    scenarioId: null,
    integrationAccountId: null,
    config: defaultConfigForProvider(definition),
  };
}

export function profileFormFromProfile(profile: ChannelProfile): ChannelProfileFormState {
  return {
    providerType: profile.providerType,
    displayName: profile.displayName,
    status: profile.status,
    inboundEnabled: profile.inboundEnabled,
    assistantId: profile.assistantBinding?.assistantId ?? null,
    scenarioId: profile.assistantBinding?.scenarioId ?? null,
    integrationAccountId: profile.accountId,
    config: cloneJsonObject(profile.config),
  };
}

export function accountOptionsForChannelProvider(
  accounts: IntegrationAccount[],
  providerType: string | null | undefined,
): ChannelProviderAccountOption[] {
  if (!providerType) {
    return [];
  }

  return accounts
    .filter((account) => (
      account.subjectType === 'CHANNEL_PROVIDER'
      && account.subjectId === providerType
      && account.status === 'ENABLED'
      && !hardBlockedCredentialStatuses.has(account.credentialStatus)
    ))
    .map((account) => ({
      label: riskCredentialStatuses.has(account.credentialStatus)
        ? `${account.name} (${account.credentialStatus})`
        : account.name,
      value: account.id,
      credentialStatus: account.credentialStatus,
    }));
}

export function currentProfileAccountHardBlock(
  profile: ChannelProfile | null | undefined,
  nextIntegrationAccountId: string | null | undefined,
): string | null {
  const summary = profile?.integrationAccount;
  if (!summary?.availabilityHardBlock) {
    return null;
  }
  return nextIntegrationAccountId === summary.id ? summary.availabilityHardBlock : null;
}

function readNumber(value: unknown, fallback: number | null): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function readString(value: unknown, fallback: string | null): string | null {
  return typeof value === 'string' && value.trim() ? value : fallback;
}

function scheduleType(value: unknown): ChannelProviderJobScheduleType {
  return value === 'CRON' || value === 'MANUAL' || value === 'INTERVAL' ? value : 'INTERVAL';
}

export function createJobFormState(
  definition: ChannelProviderDefinition['jobDefinitions'][number],
  existingJob: ChannelProviderJobConfig | null | undefined,
): ChannelProviderJobFormState {
  if (existingJob) {
    return {
      enabled: existingJob.status === 'ACTIVE' || existingJob.status === 'RUNNING',
      scheduleType: existingJob.scheduleConfig.scheduleType,
      intervalSeconds: existingJob.scheduleConfig.intervalSeconds,
      cronExpression: existingJob.scheduleConfig.cronExpression,
      timezone: existingJob.scheduleConfig.timezone,
      jobTimeoutSeconds: existingJob.scheduleConfig.jobTimeoutSeconds,
      jobConfig: cloneJsonObject(existingJob.scheduleConfig.jobConfig),
    };
  }

  const defaultSchedule = definition.defaultSchedule ?? {};
  return {
    enabled: definition.defaultEnabled === true,
    scheduleType: scheduleType(defaultSchedule.scheduleType),
    intervalSeconds: readNumber(defaultSchedule.intervalSeconds, 60),
    cronExpression: readString(defaultSchedule.cronExpression, null),
    timezone: readString(defaultSchedule.timezone, 'UTC') ?? 'UTC',
    jobTimeoutSeconds: readNumber(defaultSchedule.jobTimeoutSeconds, definition.defaultJobTimeoutSeconds),
    jobConfig: cloneJsonObject(defaultSchedule.jobConfig),
  };
}

export function buildProviderJobWritePayload(
  form: ChannelProviderJobFormState,
  existingJob: ChannelProviderJobConfig | null | undefined,
): ChannelProviderJobConfigWritePayload {
  return {
    scheduleConfig: {
      enabled: form.enabled,
      scheduleType: form.scheduleType,
      intervalSeconds: form.scheduleType === 'INTERVAL' ? form.intervalSeconds : null,
      cronExpression: form.scheduleType === 'CRON' ? form.cronExpression : null,
      timezone: form.timezone || 'UTC',
      jobTimeoutSeconds: form.jobTimeoutSeconds,
      jobConfig: cloneJsonObject(form.jobConfig),
    },
    expectedRevision: existingJob?.revision ?? null,
  };
}

export function parseJsonObject(rawValue: string): JsonObjectParseResult {
  if (!rawValue.trim()) {
    return {
      valid: true,
      value: {},
      error: null,
    };
  }

  try {
    const parsed = JSON.parse(rawValue) as unknown;
    if (!isPlainObject(parsed)) {
      return {
        valid: false,
        value: {},
        error: 'JSON must be an object',
      };
    }
    return {
      valid: true,
      value: parsed,
      error: null,
    };
  } catch {
    return {
      valid: false,
      value: {},
      error: 'Invalid JSON',
    };
  }
}

export function buildTemplateBindingWritePayload(form: TemplateBindingFormState): ChannelTemplateBindingWritePayload | null {
  const variableSchema = parseJsonObject(form.variableSchemaJson);
  if (!variableSchema.valid) {
    return null;
  }

  return {
    externalTemplateId: form.externalTemplateId.trim(),
    externalTemplateVersion: form.externalTemplateVersion?.trim() || null,
    variableSchema: variableSchema.value,
    displayName: form.displayName.trim(),
    externalEditUrl: form.externalEditUrl?.trim() || null,
    enabled: form.enabled,
    expectedRevision: form.expectedRevision ?? null,
  };
}
