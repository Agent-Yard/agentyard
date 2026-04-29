import Ajv2020, { type ErrorObject, type ValidateFunction } from 'ajv/dist/2020';

export type SchemaDrivenFormMode = 'config' | 'credential';
export type JsonObject = Record<string, unknown>;

export interface SchemaDrivenFormOption {
  label: string;
  value: string | number | boolean;
}

export interface SchemaDrivenFormVisibilityCondition {
  field: string;
  operator: 'equals' | 'notEquals' | 'in' | 'notIn' | 'exists' | 'notExists';
  value?: unknown;
}

export interface SchemaDrivenFormUiField {
  key: string;
  label: string;
  component: string;
  description?: string;
  placeholder?: string;
  required?: boolean;
  defaultValue?: unknown;
  options?: SchemaDrivenFormOption[];
  visibilityCondition?: SchemaDrivenFormVisibilityCondition;
  validationMessage?: string;
  secret?: boolean;
  readOnly?: boolean;
  order?: number;
  group?: string;
}

export interface SchemaDrivenFormValidationResult {
  valid: boolean;
  fieldErrors: Record<string, string[]>;
  errors: ErrorObject[];
}

export interface SchemaDrivenFormValidationOptions {
  mode?: SchemaDrivenFormMode;
  uiSchema?: SchemaDrivenFormUiField[];
}

export interface SecretBoundaryViolation {
  pointer: string;
  reason: string;
}

export type ApiValidationErrors =
  | null
  | undefined
  | Record<string, unknown>
  | Array<{ field?: string; path?: string; pointer?: string; message?: string }>;

const secretLikeKeyNames = new Set([
  'secret',
  'externalsecretref',
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

let cachedAjv: Ajv2020 | null = null;
const validatorCache = new WeakMap<object, ValidateFunction>();

function ajv() {
  if (cachedAjv) {
    return cachedAjv;
  }

  cachedAjv = new Ajv2020({
    allErrors: true,
    strict: false,
  });
  cachedAjv.addFormat('email', {
    type: 'string',
    validate: (value: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value),
  });
  cachedAjv.addFormat('uri', {
    type: 'string',
    validate: (value: string) => {
      try {
        const url = new URL(value);
        return Boolean(url.protocol && url.host);
      } catch {
        return false;
      }
    },
  });
  cachedAjv.addFormat('uri-reference', {
    type: 'string',
    validate: (value: string) => value.length > 0,
  });
  cachedAjv.addFormat('date-time', {
    type: 'string',
    validate: (value: string) => !Number.isNaN(Date.parse(value)),
  });
  return cachedAjv;
}

export function decodeJsonPointer(pointer: string): string[] {
  if (pointer === '') {
    return [];
  }
  if (!pointer.startsWith('/')) {
    throw new Error(`JSON Pointer must start with "/": ${pointer}`);
  }
  return pointer
    .slice(1)
    .split('/')
    .map((segment) => segment.replace(/~1/g, '/').replace(/~0/g, '~'));
}

export function encodeJsonPointerSegment(segment: string): string {
  return segment.replace(/~/g, '~0').replace(/\//g, '~1');
}

export function joinJsonPointer(basePointer: string, segment: string): string {
  const encoded = encodeJsonPointerSegment(segment);
  return basePointer === '' ? `/${encoded}` : `${basePointer}/${encoded}`;
}

export function getJsonPointerValue(source: unknown, pointer: string): unknown {
  return decodeJsonPointer(pointer).reduce<unknown>((current, segment) => {
    if (current === null || current === undefined || typeof current !== 'object') {
      return undefined;
    }
    return (current as Record<string, unknown>)[segment];
  }, source);
}

function cloneContainer(value: unknown): JsonObject {
  if (Array.isArray(value)) {
    return [...value] as unknown as JsonObject;
  }
  if (value !== null && typeof value === 'object') {
    return { ...(value as JsonObject) };
  }
  return {};
}

export function setJsonPointerValue(source: unknown, pointer: string, value: unknown): JsonObject {
  const segments = decodeJsonPointer(pointer);
  if (segments.length === 0) {
    return value !== null && typeof value === 'object' && !Array.isArray(value)
      ? { ...(value as JsonObject) }
      : {};
  }

  const root = cloneContainer(source);
  let cursor = root;
  let sourceCursor = source;

  segments.forEach((segment, index) => {
    const isLeaf = index === segments.length - 1;
    if (isLeaf) {
      if (value === undefined) {
        delete cursor[segment];
      } else {
        cursor[segment] = value;
      }
      return;
    }

    const nextSource = sourceCursor !== null && typeof sourceCursor === 'object'
      ? (sourceCursor as JsonObject)[segment]
      : undefined;
    const next = cloneContainer(nextSource);
    cursor[segment] = next;
    cursor = next;
    sourceCursor = nextSource;
  });

  return root;
}

export function isPresent(value: unknown): boolean {
  return value !== undefined && value !== null && value !== '';
}

export function evaluateVisibilityCondition(
  condition: SchemaDrivenFormVisibilityCondition | undefined,
  formValue: unknown,
): boolean {
  if (!condition) {
    return true;
  }

  const currentValue = getJsonPointerValue(formValue, condition.field);
  switch (condition.operator) {
    case 'equals':
      return currentValue === condition.value;
    case 'notEquals':
      return currentValue !== condition.value;
    case 'in':
      return Array.isArray(condition.value) && condition.value.includes(currentValue as never);
    case 'notIn':
      return Array.isArray(condition.value) && !condition.value.includes(currentValue as never);
    case 'exists':
      return isPresent(currentValue);
    case 'notExists':
      return !isPresent(currentValue);
    default:
      return false;
  }
}

function normalizeSecretKeyName(value: string): string {
  return value.replace(/[^a-zA-Z0-9]/g, '').toLowerCase();
}

function pointerContainsSecretLikeKey(pointer: string): boolean {
  return decodeJsonPointer(pointer).some((segment) => secretLikeKeyNames.has(normalizeSecretKeyName(segment)));
}

function pointerContainsExternalSecretRef(pointer: string): boolean {
  return decodeJsonPointer(pointer).some((segment) => normalizeSecretKeyName(segment) === 'externalsecretref');
}

export function isSecretField(field: SchemaDrivenFormUiField): boolean {
  return field.secret === true
    || field.component === 'password'
    || pointerContainsSecretLikeKey(field.key);
}

export function getSecretBoundaryViolations(
  uiSchema: SchemaDrivenFormUiField[],
  mode: SchemaDrivenFormMode = 'config',
): SecretBoundaryViolation[] {
  const violations: SecretBoundaryViolation[] = [];

  for (const field of uiSchema) {
    if (pointerContainsExternalSecretRef(field.key)) {
      violations.push({
        pointer: field.key,
        reason: 'externalSecretRef is not renderable in Web forms',
      });
      continue;
    }

    if (mode === 'config' && isSecretField(field)) {
      violations.push({
        pointer: field.key,
        reason: 'Secret fields are only allowed in credential forms',
      });
    }
  }

  return violations;
}

export function isRenderableField(field: SchemaDrivenFormUiField, mode: SchemaDrivenFormMode): boolean {
  if (pointerContainsExternalSecretRef(field.key)) {
    return false;
  }
  return mode === 'credential' || !isSecretField(field);
}

function stripSecretLikeModelKeys(value: unknown, stripAllSecretLike: boolean): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => stripSecretLikeModelKeys(item, stripAllSecretLike));
  }
  if (value === null || typeof value !== 'object') {
    return value;
  }

  return Object.fromEntries(
    Object.entries(value as JsonObject)
      .filter(([key]) => {
        const normalized = normalizeSecretKeyName(key);
        if (normalized === 'externalsecretref') {
          return false;
        }
        return !stripAllSecretLike || !secretLikeKeyNames.has(normalized);
      })
      .map(([key, child]) => [key, stripSecretLikeModelKeys(child, stripAllSecretLike)]),
  );
}

export function sanitizeInitialModelValue(
  modelValue: JsonObject,
  uiSchema: SchemaDrivenFormUiField[],
  mode: SchemaDrivenFormMode = 'config',
): JsonObject {
  const base = stripSecretLikeModelKeys(modelValue, true) as JsonObject;

  if (mode !== 'credential') {
    return base;
  }

  return uiSchema.reduce<JsonObject>((current, field) => {
    if (!isRenderableField(field, mode) || isSecretField(field)) {
      return setJsonPointerValue(current, field.key, undefined);
    }
    return current;
  }, base);
}

function compileValidator(schema: object): ValidateFunction {
  const existing = validatorCache.get(schema);
  if (existing) {
    return existing;
  }
  const compiled = ajv().compile(schema);
  validatorCache.set(schema, compiled);
  return compiled;
}

function errorPointer(error: ErrorObject): string {
  if (error.keyword === 'required' && typeof error.params.missingProperty === 'string') {
    return joinJsonPointer(error.instancePath, error.params.missingProperty);
  }
  if (
    error.keyword === 'additionalProperties'
    && typeof error.params.additionalProperty === 'string'
  ) {
    return joinJsonPointer(error.instancePath, error.params.additionalProperty);
  }
  return error.instancePath || '';
}

function addFieldError(target: Record<string, string[]>, pointer: string, message: string) {
  target[pointer] ??= [];
  target[pointer].push(message);
}

export function mapAjvErrors(errors: ErrorObject[] | null | undefined): Record<string, string[]> {
  const mapped: Record<string, string[]> = {};
  for (const error of errors ?? []) {
    addFieldError(mapped, errorPointer(error), error.message ?? 'Invalid value');
  }
  return mapped;
}

export function validateSchemaDrivenForm(
  schema: unknown,
  value: unknown,
  options: SchemaDrivenFormValidationOptions = {},
): SchemaDrivenFormValidationResult {
  const boundaryFieldErrors: Record<string, string[]> = {};
  for (const violation of getSecretBoundaryViolations(options.uiSchema ?? [], options.mode ?? 'config')) {
    addFieldError(boundaryFieldErrors, violation.pointer, violation.reason);
  }

  if (schema === null || typeof schema !== 'object') {
    return {
      valid: Object.keys(boundaryFieldErrors).length === 0,
      fieldErrors: boundaryFieldErrors,
      errors: [],
    };
  }

  const validate = compileValidator(schema as object);
  const ajvValid = validate(value);
  const errors = ajvValid ? [] : [...(validate.errors ?? [])];
  const fieldErrors = {
    ...mapAjvErrors(errors),
  };
  for (const [pointer, messages] of Object.entries(boundaryFieldErrors)) {
    fieldErrors[pointer] ??= [];
    fieldErrors[pointer].push(...messages);
  }

  return {
    valid: ajvValid && Object.keys(boundaryFieldErrors).length === 0,
    fieldErrors,
    errors,
  };
}

function pathToPointer(path: string): string {
  const trimmed = path.trim();
  if (!trimmed) {
    return '';
  }
  if (trimmed.startsWith('/')) {
    return trimmed;
  }

  return `/${trimmed
    .split('.')
    .filter(Boolean)
    .map(encodeJsonPointerSegment)
    .join('/')}`;
}

function apiErrorMessage(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.flatMap(apiErrorMessage);
  }
  if (typeof value === 'string') {
    return [value];
  }
  if (value && typeof value === 'object' && typeof (value as { message?: unknown }).message === 'string') {
    return [(value as { message: string }).message];
  }
  return [];
}

export function mapApiValidationErrors(apiErrors: ApiValidationErrors): Record<string, string[]> {
  const mapped: Record<string, string[]> = {};
  if (!apiErrors) {
    return mapped;
  }

  const rawFieldErrors = Array.isArray(apiErrors)
    ? null
    : (apiErrors as { fieldErrors?: unknown }).fieldErrors;
  const fieldErrorRecord = rawFieldErrors
    && typeof rawFieldErrors === 'object'
    && !Array.isArray(rawFieldErrors)
    ? rawFieldErrors as Record<string, unknown>
    : null;
  const entries = Array.isArray(apiErrors)
    ? apiErrors
    : Array.isArray(rawFieldErrors)
      ? rawFieldErrors as Array<{ field?: string; path?: string; pointer?: string; message?: string }>
      : [];

  for (const entry of entries) {
    const path = entry.pointer ?? entry.path ?? entry.field;
    if (!path) {
      continue;
    }
    for (const message of apiErrorMessage(entry.message ?? entry)) {
      addFieldError(mapped, pathToPointer(path), message);
    }
  }

  if (fieldErrorRecord) {
    for (const [path, message] of Object.entries(fieldErrorRecord)) {
      for (const item of apiErrorMessage(message)) {
        addFieldError(mapped, pathToPointer(path), item);
      }
    }
  }

  if (!Array.isArray(apiErrors)) {
    for (const [path, message] of Object.entries(apiErrors)) {
      if (path === 'fieldErrors') {
        continue;
      }
      for (const item of apiErrorMessage(message)) {
        addFieldError(mapped, pathToPointer(path), item);
      }
    }
  }

  return mapped;
}

export function schemaAtPointer(schema: unknown, pointer: string): JsonObject | null {
  let current = schema;
  for (const segment of decodeJsonPointer(pointer)) {
    if (current === null || typeof current !== 'object') {
      return null;
    }
    const properties = (current as { properties?: unknown }).properties;
    if (properties === null || typeof properties !== 'object') {
      return null;
    }
    current = (properties as JsonObject)[segment];
  }
  return current !== null && typeof current === 'object' ? current as JsonObject : null;
}

export function isRequiredBySchema(schema: unknown, pointer: string): boolean {
  const segments = decodeJsonPointer(pointer);
  if (segments.length === 0) {
    return false;
  }

  let current = schema;
  for (const segment of segments.slice(0, -1)) {
    if (current === null || typeof current !== 'object') {
      return false;
    }
    const properties = (current as { properties?: unknown }).properties;
    if (properties === null || typeof properties !== 'object') {
      return false;
    }
    current = (properties as JsonObject)[segment];
  }

  if (current === null || typeof current !== 'object') {
    return false;
  }
  const required = (current as { required?: unknown }).required;
  return Array.isArray(required) && required.includes(segments[segments.length - 1]);
}

export function fieldOptions(field: SchemaDrivenFormUiField, schema: unknown): SchemaDrivenFormOption[] {
  if (field.options?.length) {
    return field.options;
  }

  const fieldSchema = schemaAtPointer(schema, field.key);
  const enumValues = fieldSchema?.enum;
  if (Array.isArray(enumValues)) {
    return enumValues
      .filter((value): value is string | number | boolean => ['string', 'number', 'boolean'].includes(typeof value))
      .map((value) => ({ label: String(value), value }));
  }

  const oneOf = fieldSchema?.oneOf;
  if (Array.isArray(oneOf)) {
    return oneOf.flatMap((item) => {
      if (!item || typeof item !== 'object' || !('const' in item)) {
        return [];
      }
      const value = (item as { const: unknown }).const;
      if (!['string', 'number', 'boolean'].includes(typeof value)) {
        return [];
      }
      const title = (item as { title?: unknown }).title;
      return [{ label: typeof title === 'string' ? title : String(value), value: value as string | number | boolean }];
    });
  }

  return [];
}

export function displayComponent(field: SchemaDrivenFormUiField, mode: SchemaDrivenFormMode): string {
  if (mode === 'credential' && isSecretField(field)) {
    return 'password';
  }
  return field.component;
}
