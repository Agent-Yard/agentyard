import { describe, expect, it } from 'vitest';
import {
  canCreateCredential,
  canRevokeCredential,
  canRotateCredential,
  canValidateCredential,
  descriptorOptions,
  findAccountDefinition,
  integrationAccountDefinitions,
  isBlockingCredentialStatus,
  isRiskCredentialStatus,
  supportsCredentialManagement,
} from './integrationAccountDefinition';
import type {
  ChannelProviderDefinition,
  IntegrationAccount,
  ToolConnectorDefinition,
} from '../types';

const toolConnector: ToolConnectorDefinition = {
  connectorType: 'enterprise.acme.crm',
  title: 'Acme CRM',
  description: 'CRM connector',
  definitionDigest: 'sha256:tool',
  accountConfigSchema: { type: 'object' },
  accountConfigUiSchema: [{ key: '/tenantId', label: 'Tenant', component: 'text' }],
  credentialCapability: {
    supported: true,
    mode: 'REMOTE_LIFECYCLE',
    credentialSchema: { type: 'object', required: ['apiKey'] },
    credentialUiSchema: [{ key: '/apiKey', label: 'API Key', component: 'password', secret: true }],
  },
  configSchema: { type: 'object' },
  configUiSchema: [],
  operationMappingSchema: { type: 'object' },
  operationMappingUiSchema: [],
};

const channelProvider: ChannelProviderDefinition = {
  providerType: 'enterprise.acme.internal-im',
  title: 'Acme Internal IM',
  description: 'Internal messaging provider',
  definitionDigest: 'sha256:provider',
  accountConfigSchema: { type: 'object' },
  accountConfigUiSchema: [],
  credentialCapability: {
    supported: false,
    mode: null,
    credentialSchema: null,
    credentialUiSchema: [],
  },
  configSchema: { type: 'object' },
  configUiSchema: [],
  defaultConfig: {},
  jobDefinitions: [],
};

function account(overrides: Partial<IntegrationAccount>): IntegrationAccount {
  return {
    id: 'account-1',
    subjectType: 'TOOL_CONNECTOR',
    subjectId: 'enterprise.acme.crm',
    name: 'CRM account',
    status: 'ENABLED',
    config: {},
    hasExternalSecretRef: false,
    credentialConfigured: false,
    credentialStatus: 'NOT_CONFIGURED',
    createdAt: '2026-04-01T00:00:00Z',
    updatedAt: '2026-04-01T00:00:00Z',
    ...overrides,
  };
}

describe('IntegrationAccountPage definition rules', () => {
  it('builds descriptor choices from extension definitions instead of static connector enums', () => {
    const definitions = integrationAccountDefinitions([toolConnector], [channelProvider]);

    expect(descriptorOptions(definitions, 'TOOL_CONNECTOR')).toEqual([{
      label: 'Acme CRM (enterprise.acme.crm)',
      value: 'enterprise.acme.crm',
      description: 'CRM connector',
    }]);
    expect(descriptorOptions(definitions, 'CHANNEL_PROVIDER')).toEqual([{
      label: 'Acme Internal IM (enterprise.acme.internal-im)',
      value: 'enterprise.acme.internal-im',
      description: 'Internal messaging provider',
    }]);
    expect(findAccountDefinition(definitions, 'TOOL_CONNECTOR', 'enterprise.acme.crm')?.title).toBe('Acme CRM');
  });

  it('only treats supported definitions with credential schema as credential manageable', () => {
    const definitions = integrationAccountDefinitions([toolConnector], [channelProvider]);

    expect(supportsCredentialManagement(findAccountDefinition(definitions, 'TOOL_CONNECTOR', 'enterprise.acme.crm'))).toBe(true);
    expect(supportsCredentialManagement(findAccountDefinition(definitions, 'CHANNEL_PROVIDER', 'enterprise.acme.internal-im'))).toBe(false);
  });

  it('marks revoked states as hard blockers and not-configured style states as risks', () => {
    expect(isBlockingCredentialStatus('REVOKE_FAILED')).toBe(true);
    expect(isBlockingCredentialStatus('REVOKED')).toBe(true);
    expect(isRiskCredentialStatus('NOT_CONFIGURED')).toBe(true);
    expect(isRiskCredentialStatus('VALIDATION_FAILED')).toBe(true);
    expect(isRiskCredentialStatus('ROTATION_REQUIRED')).toBe(true);
    expect(isRiskCredentialStatus('ACTIVE')).toBe(false);
  });

  it('does not offer create or reset style actions for revoked accounts', () => {
    const definition = integrationAccountDefinitions([toolConnector], [])[0];
    const revokedAccount = account({
      credentialConfigured: false,
      credentialStatus: 'REVOKED',
    });

    expect(canCreateCredential(revokedAccount, definition)).toBe(false);
    expect(canRotateCredential(revokedAccount, definition)).toBe(false);
    expect(canValidateCredential(revokedAccount, definition)).toBe(false);
    expect(canRevokeCredential(revokedAccount, definition)).toBe(false);
  });

  it('allows create for unconfigured credentials and rotate/validate/revoke for configured credentials', () => {
    const definition = integrationAccountDefinitions([toolConnector], [])[0];

    expect(canCreateCredential(account({ credentialStatus: 'NOT_CONFIGURED' }), definition)).toBe(true);
    expect(canCreateCredential(account({ credentialStatus: 'VALIDATION_FAILED' }), definition)).toBe(true);
    expect(canCreateCredential(account({ credentialConfigured: true, credentialStatus: 'ACTIVE' }), definition)).toBe(false);

    const configured = account({ credentialConfigured: true, credentialStatus: 'ACTIVE' });
    expect(canRotateCredential(configured, definition)).toBe(true);
    expect(canValidateCredential(configured, definition)).toBe(true);
    expect(canRevokeCredential(configured, definition)).toBe(true);
  });
});
