import type {
  ChannelProviderDefinition,
  IntegrationAccount,
  IntegrationAccountCredentialStatus,
  IntegrationAccountSubjectType,
  ToolConnectorDefinition,
} from '../types';

export interface IntegrationAccountDefinition {
  subjectType: IntegrationAccountSubjectType;
  subjectId: string;
  title: string;
  description: string | null;
  accountConfigSchema: Record<string, unknown>;
  accountConfigUiSchema: Record<string, unknown>[];
  credentialCapability: {
    supported: boolean;
    mode: string | null;
    credentialSchema: Record<string, unknown> | null;
    credentialUiSchema: Record<string, unknown>[];
  };
}

export interface IntegrationAccountDescriptorOption {
  label: string;
  value: string;
  description: string | null;
}

export const blockingCredentialStatuses = new Set<IntegrationAccountCredentialStatus>([
  'REVOKE_FAILED',
  'REVOKED',
]);

export const riskCredentialStatuses = new Set<IntegrationAccountCredentialStatus>([
  'NOT_CONFIGURED',
  'VALIDATION_FAILED',
  'ROTATION_REQUIRED',
]);

export function toolConnectorAccountDefinition(definition: ToolConnectorDefinition): IntegrationAccountDefinition {
  return {
    subjectType: 'TOOL_CONNECTOR',
    subjectId: definition.connectorType,
    title: definition.title,
    description: definition.description,
    accountConfigSchema: definition.accountConfigSchema,
    accountConfigUiSchema: definition.accountConfigUiSchema,
    credentialCapability: definition.credentialCapability,
  };
}

export function channelProviderAccountDefinition(definition: ChannelProviderDefinition): IntegrationAccountDefinition {
  return {
    subjectType: 'CHANNEL_PROVIDER',
    subjectId: definition.providerType,
    title: definition.title,
    description: definition.description,
    accountConfigSchema: definition.accountConfigSchema,
    accountConfigUiSchema: definition.accountConfigUiSchema,
    credentialCapability: definition.credentialCapability,
  };
}

export function integrationAccountDefinitions(
  toolConnectors: ToolConnectorDefinition[],
  channelProviders: ChannelProviderDefinition[],
): IntegrationAccountDefinition[] {
  return [
    ...toolConnectors.map(toolConnectorAccountDefinition),
    ...channelProviders.map(channelProviderAccountDefinition),
  ];
}

export function descriptorOptions(
  definitions: IntegrationAccountDefinition[],
  subjectType: IntegrationAccountSubjectType,
): IntegrationAccountDescriptorOption[] {
  return definitions
    .filter((definition) => definition.subjectType === subjectType)
    .map((definition) => ({
      label: `${definition.title} (${definition.subjectId})`,
      value: definition.subjectId,
      description: definition.description,
    }));
}

export function findAccountDefinition(
  definitions: IntegrationAccountDefinition[],
  subjectType: IntegrationAccountSubjectType,
  subjectId: string,
): IntegrationAccountDefinition | null {
  return definitions.find((definition) => (
    definition.subjectType === subjectType && definition.subjectId === subjectId
  )) ?? null;
}

export function hasCredentialSchema(definition: IntegrationAccountDefinition | null): boolean {
  const schema = definition?.credentialCapability.credentialSchema;
  return Boolean(schema && typeof schema === 'object' && !Array.isArray(schema));
}

export function supportsCredentialManagement(definition: IntegrationAccountDefinition | null): boolean {
  return definition?.credentialCapability.supported === true && hasCredentialSchema(definition);
}

export function isBlockingCredentialStatus(status: IntegrationAccountCredentialStatus): boolean {
  return blockingCredentialStatuses.has(status);
}

export function isRiskCredentialStatus(status: IntegrationAccountCredentialStatus): boolean {
  return riskCredentialStatuses.has(status);
}

export function canCreateCredential(account: IntegrationAccount, definition: IntegrationAccountDefinition | null): boolean {
  return supportsCredentialManagement(definition)
    && !account.credentialConfigured
    && !account.hasExternalSecretRef
    && (account.credentialStatus === 'NOT_CONFIGURED' || account.credentialStatus === 'VALIDATION_FAILED');
}

export function canRotateCredential(account: IntegrationAccount, definition: IntegrationAccountDefinition | null): boolean {
  return supportsCredentialManagement(definition)
    && account.credentialConfigured
    && !isBlockingCredentialStatus(account.credentialStatus);
}

export function canValidateCredential(account: IntegrationAccount, definition: IntegrationAccountDefinition | null): boolean {
  return supportsCredentialManagement(definition)
    && account.credentialConfigured
    && !isBlockingCredentialStatus(account.credentialStatus);
}

export function canRevokeCredential(account: IntegrationAccount, definition: IntegrationAccountDefinition | null): boolean {
  return supportsCredentialManagement(definition)
    && account.credentialConfigured
    && account.credentialStatus !== 'REVOKED';
}
