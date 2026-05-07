import { describe, expect, it } from 'vitest';
import {
  accountOptionsForConnector,
  resetConnectorForDefinition,
  schemaDrivenUiSchemaWithFallback,
  syncOperationMappings,
  toolConnectorDefinitionOptions,
} from './toolConnectorDefinitionForms';
import { validateSchemaDrivenForm } from './schemaDrivenForm';
import type { IntegrationAccount, ToolConnectorConfig, ToolConnectorDefinition } from '../types';

function definition(overrides: Partial<ToolConnectorDefinition> = {}): ToolConnectorDefinition {
  return {
    connectorType: 'enterprise.acme.crm',
    title: 'Acme CRM',
    description: 'CRM connector',
    definitionDigest: 'sha256:crm',
    accountConfigSchema: { type: 'object' },
    accountConfigUiSchema: [],
    credentialCapability: {
      supported: false,
      mode: null,
      credentialSchema: null,
      credentialUiSchema: [],
      supportsValidate: false,
    },
    configSchema: { type: 'object' },
    configUiSchema: [],
    operationMappingSchema: { type: 'object' },
    operationMappingUiSchema: [],
    ...overrides,
  };
}

function account(overrides: Partial<IntegrationAccount> = {}): IntegrationAccount {
  return {
    id: 'account-1',
    subjectType: 'TOOL_CONNECTOR',
    subjectId: 'enterprise.acme.crm',
    name: 'CRM Account',
    status: 'ENABLED',
    config: {},
    hasExternalSecretRef: false,
    credentialConfigured: true,
    credentialStatus: 'ACTIVE',
    createdAt: '2026-04-01T00:00:00Z',
    updatedAt: '2026-04-01T00:00:00Z',
    ...overrides,
  };
}

describe('ResourceVersionConfigEditor tool connector helpers', () => {
  it('turns definitions into select options using descriptor ids', () => {
    expect(toolConnectorDefinitionOptions([
      definition(),
      definition({
        connectorType: 'simple-http',
        title: 'Simple HTTP',
        description: null,
      }),
    ])).toEqual([
      {
        label: 'Acme CRM (enterprise.acme.crm)',
        value: 'enterprise.acme.crm',
        description: 'CRM connector',
      },
      {
        label: 'Simple HTTP (simple-http)',
        value: 'simple-http',
        description: null,
      },
    ]);
  });

  it('filters account options by descriptor id and excludes hard-blocked credential statuses', () => {
    const options = accountOptionsForConnector([
      account(),
      account({ id: 'account-2', name: 'CRM Warning', credentialStatus: 'ROTATION_REQUIRED' }),
      account({ id: 'account-3', name: 'CRM Revoked', credentialStatus: 'REVOKED' }),
      account({ id: 'account-4', name: 'CRM Revoke Failed', credentialStatus: 'REVOKE_FAILED' }),
      account({ id: 'account-5', subjectId: 'simple-http', name: 'Simple HTTP Account' }),
      account({ id: 'account-6', subjectType: 'CHANNEL_PROVIDER', name: 'Provider Account' }),
      account({ id: 'account-7', status: 'DISABLED', name: 'Disabled Account' }),
    ], 'enterprise.acme.crm');

    expect(options).toEqual([
      { label: 'CRM Account', value: 'account-1', credentialStatus: 'ACTIVE' },
      { label: 'CRM Warning (ROTATION_REQUIRED)', value: 'account-2', credentialStatus: 'ROTATION_REQUIRED' },
    ]);
  });

  it('resets connector config, account, and operation mappings without legacy enum aliasing', () => {
    const connector: ToolConnectorConfig = {
      connectorType: 'legacy-uppercase-id',
      accountId: 'account-1',
      timeoutSeconds: 30,
      retryPolicy: 'EXPONENTIAL_BACKOFF',
      config: { stale: 'old-value' },
      operationMappings: {
        oldOperation: { path: '/old' },
      },
    };

    resetConnectorForDefinition(connector, 'simple-http', ['lookupCustomer', '']);

    expect(connector).toEqual({
      connectorType: 'simple-http',
      accountId: null,
      timeoutSeconds: 30,
      retryPolicy: 'EXPONENTIAL_BACKOFF',
      config: {},
      operationMappings: {
        lookupCustomer: {},
      },
    });
  });

  it('keeps operation mappings aligned with current operation names', () => {
    const connector: ToolConnectorConfig = {
      connectorType: 'simple-http',
      accountId: null,
      timeoutSeconds: 30,
      retryPolicy: 'NONE',
      config: {},
      operationMappings: {
        oldOperation: { path: '/old' },
        lookupCustomer: { path: '/customers' },
      },
    };

    syncOperationMappings(connector, ['lookupCustomer', 'createTicket', 'lookupCustomer', '']);

    expect(connector.operationMappings).toEqual({
      lookupCustomer: { path: '/customers' },
      createTicket: {},
    });
  });

  it('does not replace operation mappings when sync has no effective changes', () => {
    const operationMappings = {
      lookupCustomer: { path: '/customers' },
      createTicket: {},
    };
    const connector: ToolConnectorConfig = {
      connectorType: 'simple-http',
      accountId: null,
      timeoutSeconds: 30,
      retryPolicy: 'NONE',
      config: {},
      operationMappings,
    };

    syncOperationMappings(connector, ['lookupCustomer', 'createTicket']);

    expect(connector.operationMappings).toBe(operationMappings);
  });

  it('uses index-friendly row keys for blank operation names in mapping rows', () => {
    expect(['', '', 'lookupCustomer'].map((_, index) => index)).toEqual([0, 1, 2]);
  });

  it('derives a generic UI schema from JSON Schema object properties when UI schema is empty', () => {
    const schema = {
      type: 'object',
      required: ['endpoint'],
      properties: {
        endpoint: { type: 'string', title: 'Endpoint', format: 'uri', description: 'Remote endpoint' },
        method: { enum: ['GET', 'POST'] },
        timeoutSeconds: { type: 'integer' },
        headers: { type: 'object' },
      },
    };
    const uiSchema = schemaDrivenUiSchemaWithFallback(schema, [], 'Mapping JSON');

    expect(uiSchema).toEqual([
      {
        key: '/endpoint',
        label: 'Endpoint',
        description: 'Remote endpoint',
        component: 'url',
        order: 10,
      },
      {
        key: '/method',
        label: 'method',
        description: undefined,
        component: 'select',
        order: 20,
      },
      {
        key: '/timeoutSeconds',
        label: 'timeoutSeconds',
        description: undefined,
        component: 'number',
        order: 30,
      },
      {
        key: '/headers',
        label: 'headers',
        description: undefined,
        component: 'json',
        order: 40,
      },
    ]);
    expect(validateSchemaDrivenForm(schema, {}, { mode: 'config', uiSchema }).fieldErrors).toEqual(
      expect.objectContaining({
        '/endpoint': expect.arrayContaining(['must have required property \'endpoint\'']),
      }),
    );
  });

  it('falls back to a root JSON editor when schema properties are not renderable as fields', () => {
    expect(schemaDrivenUiSchemaWithFallback({
      type: 'object',
      additionalProperties: { type: 'string' },
    }, [], 'Mapping JSON')).toEqual([{
      key: '',
      label: 'Mapping JSON',
      component: 'json',
      order: 10,
    }]);
  });
});
