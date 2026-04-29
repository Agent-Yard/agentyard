import { describe, expect, it } from 'vitest';
import {
  displayComponent,
  evaluateVisibilityCondition,
  getJsonPointerValue,
  getSecretBoundaryViolations,
  mapApiValidationErrors,
  sanitizeInitialModelValue,
  setJsonPointerValue,
  validateSchemaDrivenForm,
} from './schemaDrivenForm';
import type { SchemaDrivenFormUiField } from './schemaDrivenForm';

describe('schemaDrivenForm helpers', () => {
  it('maps JSON Pointer fields through nested values and RFC 6901 escaping', () => {
    const value = {
      auth: {
        'mode/key': 'apiKey',
        '~token': 'secret',
      },
    };

    expect(getJsonPointerValue(value, '/auth/mode~1key')).toBe('apiKey');
    expect(getJsonPointerValue(value, '/auth/~0token')).toBe('secret');

    const updated = setJsonPointerValue(value, '/auth/mode~1key', 'oauth');

    expect(updated).toEqual({
      auth: {
        'mode/key': 'oauth',
        '~token': 'secret',
      },
    });
    expect(value.auth['mode/key']).toBe('apiKey');
  });

  it('evaluates visibility conditions without mutating JSON Schema', () => {
    const schema = {
      type: 'object',
      properties: {
        auth: {
          type: 'object',
          properties: {
            mode: { enum: ['apiKey', 'oauth'] },
          },
        },
      },
    };
    const before = JSON.stringify(schema);

    expect(evaluateVisibilityCondition(
      { field: '/auth/mode', operator: 'equals', value: 'apiKey' },
      { auth: { mode: 'apiKey' } },
    )).toBe(true);
    expect(evaluateVisibilityCondition(
      { field: '/auth/mode', operator: 'notIn', value: ['apiKey'] },
      { auth: { mode: 'apiKey' } },
    )).toBe(false);
    expect(JSON.stringify(schema)).toBe(before);
  });

  it('runs Ajv 2020 validation against the full current form object', () => {
    const schema = {
      type: 'object',
      required: ['tenantId', 'auth'],
      properties: {
        tenantId: { type: 'string', minLength: 1 },
        auth: {
          type: 'object',
          required: ['mode', 'apiKey'],
          properties: {
            mode: { const: 'apiKey' },
            apiKey: { type: 'string', minLength: 8 },
          },
        },
      },
    };

    const result = validateSchemaDrivenForm(schema, {
      tenantId: '',
      auth: { mode: 'apiKey', apiKey: 'short' },
    });

    expect(result.valid).toBe(false);
    expect(result.fieldErrors['/tenantId']?.[0]).toContain('must NOT have fewer than 1 characters');
    expect(result.fieldErrors['/auth/apiKey']?.[0]).toContain('must NOT have fewer than 8 characters');
  });

  it('maps API validation errors back to JSON Pointer keyed controls', () => {
    expect(mapApiValidationErrors({
      '/auth/mode': 'Unsupported mode',
      'config.tenant/id': ['Tenant is required'],
      fieldErrors: [
        { field: 'auth.apiKey', message: 'API key is invalid' },
        { pointer: '/retryPolicy', message: 'Retry policy is invalid' },
      ],
    })).toEqual({
      '/auth/mode': ['Unsupported mode'],
      '/config/tenant~1id': ['Tenant is required'],
      '/auth/apiKey': ['API key is invalid'],
      '/retryPolicy': ['Retry policy is invalid'],
    });

    expect(mapApiValidationErrors({
      fieldErrors: {
        '/auth/apiKey': ['API key is required'],
      },
    })).toEqual({
      '/auth/apiKey': ['API key is required'],
    });
  });

  it('flags secret fields in config mode and withholds initial credential secrets', () => {
    const uiSchema: SchemaDrivenFormUiField[] = [
      { key: '/tenantId', label: 'Tenant', component: 'text' },
      { key: '/apiKey', label: 'API key', component: 'password', secret: true },
      { key: '/externalSecretRef', label: 'Secret ref', component: 'text' },
      { key: '/webhookSigningSecret', label: 'Webhook secret', component: 'text' },
    ];

    expect(getSecretBoundaryViolations(uiSchema, 'config').map((violation) => violation.pointer)).toEqual([
      '/apiKey',
      '/externalSecretRef',
      '/webhookSigningSecret',
    ]);
    expect(validateSchemaDrivenForm({ type: 'object' }, { tenantId: 'tenant-1' }, {
      mode: 'config',
      uiSchema,
    })).toEqual(expect.objectContaining({
      valid: false,
      fieldErrors: expect.objectContaining({
        '/apiKey': ['Secret fields are only allowed in credential forms'],
        '/externalSecretRef': ['externalSecretRef is not renderable in Web forms'],
        '/webhookSigningSecret': ['Secret fields are only allowed in credential forms'],
      }),
    }));

    const credentialResult = getSecretBoundaryViolations(uiSchema, 'credential');

    expect(credentialResult).toEqual([
      expect.objectContaining({ pointer: '/externalSecretRef' }),
    ]);
    expect(displayComponent(uiSchema[1], 'credential')).toBe('password');

    expect(sanitizeInitialModelValue(
      { tenantId: 'tenant-1', apiKey: 'old-secret', externalSecretRef: 'vault://ref' },
      uiSchema,
      'credential',
    )).toEqual({ tenantId: 'tenant-1' });
  });
});
