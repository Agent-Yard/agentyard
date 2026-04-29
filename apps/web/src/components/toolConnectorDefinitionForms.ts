import { encodeJsonPointerSegment } from './schemaDrivenForm';
import type { SchemaDrivenFormUiField } from './schemaDrivenForm';
import type { IntegrationAccount, ToolConnectorConfig, ToolConnectorDefinition } from '../types';

const hardBlockedCredentialStatuses = new Set(['REVOKE_FAILED', 'REVOKED']);
const riskCredentialStatuses = new Set(['NOT_CONFIGURED', 'VALIDATION_FAILED', 'ROTATION_REQUIRED']);

export interface ToolConnectorOption {
  label: string;
  value: string;
  description: string | null;
}

export interface ToolConnectorAccountOption {
  label: string;
  value: string;
  credentialStatus: IntegrationAccount['credentialStatus'];
}

export function toolConnectorDefinitionOptions(definitions: ToolConnectorDefinition[]): ToolConnectorOption[] {
  return definitions.map((definition) => ({
    label: `${definition.title} (${definition.connectorType})`,
    value: definition.connectorType,
    description: definition.description,
  }));
}

export function accountOptionsForConnector(
  accounts: IntegrationAccount[],
  connectorType: string | null | undefined,
): ToolConnectorAccountOption[] {
  if (!connectorType) {
    return [];
  }

  return accounts
    .filter((account) => (
      account.subjectType === 'TOOL_CONNECTOR'
      && account.subjectId === connectorType
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

export function createEmptyToolConnector(connectorType = ''): ToolConnectorConfig {
  return {
    connectorType,
    accountId: null,
    timeoutSeconds: 15,
    retryPolicy: 'NONE',
    config: {},
    operationMappings: {},
  };
}

export function resetConnectorForDefinition(
  connector: ToolConnectorConfig,
  connectorType: string,
  operationNames: string[],
) {
  connector.connectorType = connectorType;
  connector.accountId = null;
  connector.config = {};
  connector.operationMappings = Object.fromEntries(
    operationNames
      .map((name) => name.trim())
      .filter(Boolean)
      .map((name) => [name, {}]),
  );
}

export function syncOperationMappings(
  connector: ToolConnectorConfig | null | undefined,
  operationNames: Array<string | null | undefined>,
) {
  if (!connector) {
    return;
  }

  const existingMappings = connector.operationMappings ?? {};
  const nextMappings: Record<string, Record<string, unknown>> = {};
  for (const operationName of operationNames) {
    const normalizedName = operationName?.trim();
    if (!normalizedName || nextMappings[normalizedName]) {
      continue;
    }
    nextMappings[normalizedName] = existingMappings[normalizedName] ?? {};
  }

  const existingKeys = Object.keys(existingMappings);
  const nextKeys = Object.keys(nextMappings);
  if (
    existingKeys.length === nextKeys.length
    && nextKeys.every((key) => existingMappings[key] === nextMappings[key])
  ) {
    return;
  }

  connector.operationMappings = nextMappings;
}

function schemaRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function schemaType(schema: Record<string, unknown>): string | null {
  const type = schema.type;
  if (typeof type === 'string') {
    return type;
  }
  if (Array.isArray(type)) {
    return type.find((item): item is string => typeof item === 'string') ?? null;
  }
  return null;
}

function inferComponent(schema: Record<string, unknown>): string {
  if (Array.isArray(schema.enum) || Array.isArray(schema.oneOf)) {
    return 'select';
  }

  const type = schemaType(schema);
  if (type === 'boolean') {
    return 'boolean';
  }
  if (type === 'number' || type === 'integer') {
    return 'number';
  }
  if (type === 'object' || type === 'array') {
    return 'json';
  }

  if (schema.format === 'uri' || schema.format === 'uri-reference' || schema.format === 'url') {
    return 'url';
  }
  if (schema.format === 'email') {
    return 'email';
  }
  if (schema.format === 'date-time') {
    return 'dateTime';
  }

  return 'text';
}

function schemaTitle(propertyName: string, schema: Record<string, unknown>): string {
  return typeof schema.title === 'string' && schema.title.trim()
    ? schema.title
    : propertyName;
}

export function schemaDrivenUiSchemaWithFallback(
  schema: Record<string, unknown> | null | undefined,
  uiSchema: Record<string, unknown>[] | null | undefined,
  rootLabel: string,
): SchemaDrivenFormUiField[] {
  if (uiSchema?.length) {
    return uiSchema as unknown as SchemaDrivenFormUiField[];
  }

  const properties = schemaRecord(schema?.properties);
  if (properties && Object.keys(properties).length > 0) {
    return Object.entries(properties).map(([propertyName, propertySchema], index) => {
      const propertySchemaRecord = schemaRecord(propertySchema) ?? {};
      return {
        key: `/${encodeJsonPointerSegment(propertyName)}`,
        label: schemaTitle(propertyName, propertySchemaRecord),
        description: typeof propertySchemaRecord.description === 'string' ? propertySchemaRecord.description : undefined,
        component: inferComponent(propertySchemaRecord),
        order: (index + 1) * 10,
      };
    });
  }

  return [{
    key: '',
    label: rootLabel,
    component: 'json',
    order: 10,
  }];
}
