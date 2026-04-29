import type { ToolConnectorConfig, ToolConnectorType } from '../types';

export type ConnectorAccountMode = 'NONE' | 'OPTIONAL' | 'REQUIRED';
export type ConnectorFieldKind = 'text' | 'number' | 'select' | 'switch';

export interface ConnectorFieldDefinition {
  key: string;
  label: string;
  kind: ConnectorFieldKind;
  span: number;
  defaultValue: unknown;
  options?: Array<{ label: string; value: string }>;
}

export interface ToolConnectorDefinition {
  connectorType: ToolConnectorType;
  label: string;
  accountMode: ConnectorAccountMode;
  accountPlaceholder: string;
  configFields: ConnectorFieldDefinition[];
  operationMappingFields: ConnectorFieldDefinition[];
  credentialTemplate: string;
}

const httpOperationMappingFields: ConnectorFieldDefinition[] = [
  {
    key: 'method',
    label: 'Method',
    kind: 'select',
    span: 5,
    defaultValue: 'POST',
    options: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((item) => ({ label: item, value: item })),
  },
  { key: 'path', label: 'Path', kind: 'text', span: 8, defaultValue: '' },
  {
    key: 'requestPlacement',
    label: '入参位置',
    kind: 'select',
    span: 5,
    defaultValue: 'JSON_BODY',
    options: [
      { label: 'JSON_BODY', value: 'JSON_BODY' },
      { label: 'QUERY', value: 'QUERY' },
    ],
  },
];

export const TOOL_CONNECTOR_DEFINITIONS: ToolConnectorDefinition[] = [
  {
    connectorType: 'SIMPLE_HTTP',
    label: 'Simple HTTP',
    accountMode: 'OPTIONAL',
    accountPlaceholder: '可选 Bearer Token 账号',
    configFields: [
      { key: 'baseUrl', label: 'Base URL', kind: 'text', span: 12, defaultValue: 'https://tool-gateway.internal' },
      { key: 'authorizationHeader', label: 'Bearer Header', kind: 'text', span: 12, defaultValue: 'Authorization' },
    ],
    operationMappingFields: httpOperationMappingFields,
    credentialTemplate: '{\n  "bearerToken": ""\n}',
  },
  {
    connectorType: 'BUSINESS_CODE_SECRET_HTTP',
    label: 'Business Code Secret HTTP',
    accountMode: 'REQUIRED',
    accountPlaceholder: '选择账号',
    configFields: [
      { key: 'baseUrl', label: 'Base URL', kind: 'text', span: 12, defaultValue: 'https://tool-gateway.internal' },
      { key: 'businessCodeField', label: 'Business Code 字段', kind: 'text', span: 6, defaultValue: 'businessCode' },
      { key: 'encryptedField', label: 'Encrypted 字段', kind: 'text', span: 6, defaultValue: 'encrypted' },
    ],
    operationMappingFields: httpOperationMappingFields,
    credentialTemplate: '{\n  "businessCode": "",\n  "secretKey": ""\n}',
  },
  {
    connectorType: 'MCP',
    label: 'MCP',
    accountMode: 'NONE',
    accountPlaceholder: '该 Connector 不使用账号',
    configFields: [
      { key: 'serverName', label: 'MCP 服务名', kind: 'text', span: 12, defaultValue: 'new-mcp-server' },
      {
        key: 'transport',
        label: 'Transport',
        kind: 'select',
        span: 12,
        defaultValue: 'STREAMABLE_HTTP',
        options: [
          { label: 'STREAMABLE_HTTP', value: 'STREAMABLE_HTTP' },
          { label: 'SSE', value: 'SSE' },
        ],
      },
      { key: 'connectionUri', label: '连接地址', kind: 'text', span: 16, defaultValue: 'https://mcp-gateway.internal/new-server' },
      { key: 'namespace', label: '命名空间', kind: 'text', span: 8, defaultValue: 'default.namespace' },
      { key: 'heartbeatSeconds', label: '心跳秒数', kind: 'number', span: 8, defaultValue: 30 },
      { key: 'internalAuthEnabled', label: '内部鉴权', kind: 'switch', span: 8, defaultValue: false },
    ],
    operationMappingFields: [
      { key: 'tool', label: 'Remote Tool', kind: 'text', span: 18, defaultValue: '' },
    ],
    credentialTemplate: '{}',
  },
];

export const DEFAULT_TOOL_CONNECTOR_TYPE: ToolConnectorType = 'SIMPLE_HTTP';

export const toolConnectorOptions = TOOL_CONNECTOR_DEFINITIONS.map((definition) => ({
  label: definition.label,
  value: definition.connectorType,
}));

export const TOOL_CONNECTOR_DESCRIPTOR_IDS: Record<ToolConnectorType, string> = {
  SIMPLE_HTTP: 'simple-http',
  BUSINESS_CODE_SECRET_HTTP: 'business-code-secret-http',
  MCP: 'mcp',
};

export function toolConnectorDefinition(connectorType: ToolConnectorType): ToolConnectorDefinition {
  return TOOL_CONNECTOR_DEFINITIONS.find((definition) => definition.connectorType === connectorType) ?? TOOL_CONNECTOR_DEFINITIONS[0];
}

export function toolConnectorDescriptorId(connectorType: ToolConnectorType): string {
  return TOOL_CONNECTOR_DESCRIPTOR_IDS[connectorType];
}

export function toolConnectorTypeForDescriptorId(descriptorId: string): ToolConnectorType {
  return TOOL_CONNECTOR_DEFINITIONS.find((definition) => TOOL_CONNECTOR_DESCRIPTOR_IDS[definition.connectorType] === descriptorId)?.connectorType
    ?? DEFAULT_TOOL_CONNECTOR_TYPE;
}

export function defaultConnectorConfig(connectorType: ToolConnectorType): Record<string, unknown> {
  return Object.fromEntries(
    toolConnectorDefinition(connectorType).configFields.map((field) => [field.key, field.defaultValue]),
  );
}

export function defaultOperationMapping(connectorType: ToolConnectorType, operationName: string): Record<string, unknown> {
  const definition = toolConnectorDefinition(connectorType);
  return Object.fromEntries(
    definition.operationMappingFields.map((field) => [
      field.key,
      field.key === 'path' ? `/tools/${operationName || 'invoke'}` : field.key === 'tool' ? operationName : field.defaultValue,
    ]),
  );
}

export function createDefaultToolConnector(connectorType: ToolConnectorType = DEFAULT_TOOL_CONNECTOR_TYPE): ToolConnectorConfig {
  return {
    connectorType,
    accountId: null,
    timeoutSeconds: 15,
    retryPolicy: 'NONE',
    config: defaultConnectorConfig(connectorType),
    operationMappings: {},
  };
}

export function credentialPlaceholder(connectorType: ToolConnectorType, isEditing: boolean): string {
  if (isEditing) {
    return '留空则保留原凭证';
  }
  return toolConnectorDefinition(connectorType).credentialTemplate;
}
