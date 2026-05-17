import { createHash } from "node:crypto";
import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const packageRoot = path.resolve(__dirname, "..");

const safeIntegerMax = 9007199254740991n;
const safeIntegerMin = -9007199254740991n;
const idempotencyPattern = /^[A-Za-z0-9._:-]{1,128}$/;
const schemaAnnotationKeywords = new Set(["title", "description", "default"]);
const schemaMapKeywords = new Set(["properties", "$defs", "definitions", "patternProperties", "dependentSchemas"]);

class CheckFailure extends Error {
  constructor(message) {
    super(message);
    this.name = "CheckFailure";
  }
}

class CanonicalJsonError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "CanonicalJsonError";
    this.code = code;
  }
}

class CanonicalJsonParser {
  constructor(raw) {
    this.raw = raw;
    this.index = 0;
  }

  parse() {
    const value = this.parseValue();
    this.skipWhitespace();
    if (this.index !== this.raw.length) {
      throw new CanonicalJsonError("CANONICAL_JSON_UNSUPPORTED_VALUE", "Unexpected trailing characters");
    }
    return value;
  }

  current() {
    return this.raw[this.index];
  }

  skipWhitespace() {
    while (this.index < this.raw.length && /[\t\n\r ]/.test(this.raw[this.index])) {
      this.index += 1;
    }
  }

  parseValue() {
    this.skipWhitespace();
    const char = this.current();
    if (char === "{") return this.parseObject();
    if (char === "[") return this.parseArray();
    if (char === "\"") return this.parseString();
    if (char === "-" || (char >= "0" && char <= "9")) return this.parseNumber();
    if (this.raw.startsWith("true", this.index)) {
      this.index += 4;
      return true;
    }
    if (this.raw.startsWith("false", this.index)) {
      this.index += 5;
      return false;
    }
    if (this.raw.startsWith("null", this.index)) {
      this.index += 4;
      return null;
    }
    throw new CanonicalJsonError("CANONICAL_JSON_UNSUPPORTED_VALUE", `Unsupported JSON value at offset ${this.index}`);
  }

  parseObject() {
    this.index += 1;
    const object = Object.create(null);
    const seen = new Set();
    this.skipWhitespace();
    if (this.current() === "}") {
      this.index += 1;
      return object;
    }
    while (this.index < this.raw.length) {
      this.skipWhitespace();
      if (this.current() !== "\"") {
        throw new CanonicalJsonError("CANONICAL_JSON_UNSUPPORTED_VALUE", "Object key must be a string");
      }
      const key = this.parseString();
      if (seen.has(key)) {
        throw new CanonicalJsonError("CANONICAL_JSON_DUPLICATE_KEY", `Duplicate object key ${key}`);
      }
      seen.add(key);
      this.skipWhitespace();
      if (this.current() !== ":") {
        throw new CanonicalJsonError("CANONICAL_JSON_UNSUPPORTED_VALUE", "Expected ':' after object key");
      }
      this.index += 1;
      object[key] = this.parseValue();
      this.skipWhitespace();
      if (this.current() === "}") {
        this.index += 1;
        return object;
      }
      if (this.current() !== ",") {
        throw new CanonicalJsonError("CANONICAL_JSON_UNSUPPORTED_VALUE", "Expected ',' or '}' in object");
      }
      this.index += 1;
    }
    throw new CanonicalJsonError("CANONICAL_JSON_UNSUPPORTED_VALUE", "Unterminated object");
  }

  parseArray() {
    this.index += 1;
    const array = [];
    this.skipWhitespace();
    if (this.current() === "]") {
      this.index += 1;
      return array;
    }
    while (this.index < this.raw.length) {
      array.push(this.parseValue());
      this.skipWhitespace();
      if (this.current() === "]") {
        this.index += 1;
        return array;
      }
      if (this.current() !== ",") {
        throw new CanonicalJsonError("CANONICAL_JSON_UNSUPPORTED_VALUE", "Expected ',' or ']' in array");
      }
      this.index += 1;
    }
    throw new CanonicalJsonError("CANONICAL_JSON_UNSUPPORTED_VALUE", "Unterminated array");
  }

  parseString() {
    this.index += 1;
    let result = "";
    while (this.index < this.raw.length) {
      const codeUnit = this.raw.charCodeAt(this.index);
      const char = this.raw[this.index];
      if (char === "\"") {
        this.index += 1;
        return result;
      }
      if (codeUnit <= 0x1f) {
        throw new CanonicalJsonError("CANONICAL_JSON_INVALID_UNICODE", "Unescaped control character in string");
      }
      if (char === "\\") {
        result += this.parseEscape();
        continue;
      }
      if (isHighSurrogate(codeUnit)) {
        const next = this.raw.charCodeAt(this.index + 1);
        if (!isLowSurrogate(next)) {
          throw new CanonicalJsonError("CANONICAL_JSON_INVALID_UNICODE", "Unpaired high surrogate in string");
        }
        result += this.raw[this.index] + this.raw[this.index + 1];
        this.index += 2;
        continue;
      }
      if (isLowSurrogate(codeUnit)) {
        throw new CanonicalJsonError("CANONICAL_JSON_INVALID_UNICODE", "Unpaired low surrogate in string");
      }
      result += char;
      this.index += 1;
    }
    throw new CanonicalJsonError("CANONICAL_JSON_INVALID_UNICODE", "Unterminated string");
  }

  parseEscape() {
    this.index += 1;
    const escaped = this.raw[this.index];
    this.index += 1;
    switch (escaped) {
      case "\"":
      case "\\":
      case "/":
        return escaped;
      case "b":
        return "\b";
      case "f":
        return "\f";
      case "n":
        return "\n";
      case "r":
        return "\r";
      case "t":
        return "\t";
      case "u":
        return this.parseUnicodeEscapeAfterU();
      default:
        throw new CanonicalJsonError("CANONICAL_JSON_INVALID_UNICODE", `Invalid escape \\${escaped}`);
    }
  }

  parseUnicodeEscapeAfterU() {
    const code = this.readHexCodeUnit();
    if (isHighSurrogate(code)) {
      if (this.raw[this.index] !== "\\" || this.raw[this.index + 1] !== "u") {
        throw new CanonicalJsonError("CANONICAL_JSON_INVALID_UNICODE", "High surrogate must be followed by low surrogate escape");
      }
      this.index += 2;
      const low = this.readHexCodeUnit();
      if (!isLowSurrogate(low)) {
        throw new CanonicalJsonError("CANONICAL_JSON_INVALID_UNICODE", "High surrogate not followed by low surrogate");
      }
      return String.fromCharCode(code, low);
    }
    if (isLowSurrogate(code)) {
      throw new CanonicalJsonError("CANONICAL_JSON_INVALID_UNICODE", "Low surrogate without high surrogate");
    }
    return String.fromCharCode(code);
  }

  readHexCodeUnit() {
    const hex = this.raw.slice(this.index, this.index + 4);
    if (!/^[0-9a-fA-F]{4}$/.test(hex)) {
      throw new CanonicalJsonError("CANONICAL_JSON_INVALID_UNICODE", "Invalid unicode escape");
    }
    this.index += 4;
    return Number.parseInt(hex, 16);
  }

  parseNumber() {
    const start = this.index;
    if (this.current() === "-") {
      this.index += 1;
    }
    if (this.current() === "0") {
      this.index += 1;
      if (/[0-9]/.test(this.current() ?? "")) {
        throw new CanonicalJsonError("CANONICAL_JSON_UNSUPPORTED_NUMBER", "Leading zero is not supported");
      }
    } else if (/[1-9]/.test(this.current() ?? "")) {
      while (/[0-9]/.test(this.current() ?? "")) {
        this.index += 1;
      }
    } else {
      throw new CanonicalJsonError("CANONICAL_JSON_UNSUPPORTED_NUMBER", "Invalid number");
    }
    if (this.current() === "." || this.current() === "e" || this.current() === "E") {
      throw new CanonicalJsonError("CANONICAL_JSON_UNSUPPORTED_NUMBER", "Only JSON integers are supported");
    }
    const token = this.raw.slice(start, this.index);
    if (token === "-0") {
      throw new CanonicalJsonError("CANONICAL_JSON_UNSUPPORTED_NUMBER", "Negative zero is not supported");
    }
    const asBigInt = BigInt(token);
    if (asBigInt < safeIntegerMin || asBigInt > safeIntegerMax) {
      throw new CanonicalJsonError("CANONICAL_JSON_UNSAFE_INTEGER", "Integer is outside the safe range");
    }
    return Number(asBigInt);
  }
}

function isHighSurrogate(codeUnit) {
  return codeUnit >= 0xd800 && codeUnit <= 0xdbff;
}

function isLowSurrogate(codeUnit) {
  return codeUnit >= 0xdc00 && codeUnit <= 0xdfff;
}

function parseCanonicalJson(raw) {
  return new CanonicalJsonParser(raw).parse();
}

function canonicalString(value) {
  if (value === null) return "null";
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value) || Object.is(value, -0)) {
      throw new CanonicalJsonError("CANONICAL_JSON_UNSUPPORTED_NUMBER", "Only safe integers are supported");
    }
    return String(value);
  }
  if (typeof value === "string") return quoteCanonicalString(value);
  if (Array.isArray(value)) {
    return `[${value.map((item) => canonicalString(item)).join(",")}]`;
  }
  if (isObject(value)) {
    const keys = Object.keys(value).sort(compareUtf16);
    return `{${keys.map((key) => `${quoteCanonicalString(key)}:${canonicalString(value[key])}`).join(",")}}`;
  }
  throw new CanonicalJsonError("CANONICAL_JSON_UNSUPPORTED_VALUE", `Unsupported value type ${typeof value}`);
}

function quoteCanonicalString(value) {
  let result = "\"";
  for (let index = 0; index < value.length; index += 1) {
    const codeUnit = value.charCodeAt(index);
    switch (codeUnit) {
      case 0x22:
        result += "\\\"";
        break;
      case 0x5c:
        result += "\\\\";
        break;
      case 0x08:
        result += "\\b";
        break;
      case 0x09:
        result += "\\t";
        break;
      case 0x0a:
        result += "\\n";
        break;
      case 0x0c:
        result += "\\f";
        break;
      case 0x0d:
        result += "\\r";
        break;
      default:
        if (codeUnit <= 0x1f) {
          result += `\\u${codeUnit.toString(16).padStart(4, "0")}`;
        } else if (isHighSurrogate(codeUnit)) {
          const next = value.charCodeAt(index + 1);
          if (!isLowSurrogate(next)) {
            throw new CanonicalJsonError("CANONICAL_JSON_INVALID_UNICODE", "Unpaired high surrogate");
          }
          result += value[index] + value[index + 1];
          index += 1;
        } else if (isLowSurrogate(codeUnit)) {
          throw new CanonicalJsonError("CANONICAL_JSON_INVALID_UNICODE", "Unpaired low surrogate");
        } else {
          result += value[index];
        }
    }
  }
  result += "\"";
  return result;
}

function compareUtf16(left, right) {
  if (left < right) return -1;
  if (left > right) return 1;
  return 0;
}

function canonicalUtf8Hex(value) {
  return Buffer.from(canonicalString(value), "utf8").toString("hex");
}

function sha256Digest(value) {
  return `sha256:${createHash("sha256").update(canonicalString(value), "utf8").digest("hex")}`;
}

function readJsonFile(filePath) {
  const raw = readFileSync(filePath, "utf8");
  try {
    return parseCanonicalJson(raw);
  } catch (error) {
    if (error instanceof CanonicalJsonError) {
      throw new CheckFailure(`${relative(filePath)} failed strict JSON parse: ${error.code} ${error.message}`);
    }
    throw error;
  }
}

function relative(filePath) {
  return path.relative(packageRoot, filePath);
}

function listJsonFiles(dir) {
  const result = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      result.push(...listJsonFiles(fullPath));
    } else if (entry.isFile() && entry.name.endsWith(".json")) {
      result.push(fullPath);
    }
  }
  return result.sort(compareUtf16);
}

function hasOwn(object, key) {
  return Object.prototype.hasOwnProperty.call(object, key);
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function assert(condition, message) {
  if (!condition) {
    throw new CheckFailure(message);
  }
}

function checkJsonAssets() {
  const jsonFiles = [
    ...listJsonFiles(path.join(packageRoot, "openapi")),
    ...listJsonFiles(path.join(packageRoot, "json-schema")),
    ...listJsonFiles(path.join(packageRoot, "examples")),
    ...listJsonFiles(path.join(packageRoot, "contract-tests", "fixtures"))
  ];
  for (const file of jsonFiles) {
    readJsonFile(file);
  }
  return jsonFiles.length;
}

function checkOpenApi() {
  const openApiPath = path.join(packageRoot, "openapi", "extension-boundary.openapi.json");
  const openApi = readJsonFile(openApiPath);
  assert(openApi.openapi === "3.1.0", "OpenAPI document must use OpenAPI 3.1.0");

  const requiredPaths = [
    "/extension/manifest",
    "/extension/health",
    "/health/live",
    "/health/ready",
    "/tools/invoke",
    "/extension/channel/outbound-frame-subscriptions",
    "/extension/channel/outbound-frames/stream",
    "/extension/channel/outbound-frames/ack",
    "/channel/run-job",
    "/credentials",
    "/credentials/rotate",
    "/credentials/revoke",
    "/credentials/validate",
    "/internal/channel-events/normalized",
    "/internal/channel-turns/normalized"
  ];
  for (const requiredPath of requiredPaths) {
    assert(hasOwn(openApi.paths, requiredPath), `OpenAPI missing path ${requiredPath}`);
  }

  const requiredSchemas = [
    "ServiceManifestEnvelope",
    "ChannelProviderDescriptor",
    "ToolConnectorDescriptor",
    "RemoteToolInvokeRequest",
    "ToolInvokeResponse",
    "ChannelOutboundFrame",
    "ChannelOutboundFrameAck",
    "ChannelOutboundFrameSubscription",
    "ChannelRunJobRequest",
    "CreateCredentialRequest",
    "RotateCredentialRequest",
    "ValidateCredentialRequest",
    "RevokeCredentialRequest",
    "CreateCredentialAccount",
    "ExistingCredentialAccount",
    "CreateCredentialResponse",
    "RotateCredentialResponse",
    "ValidateCredentialResponse",
    "RevokeCredentialResponse",
    "NormalizedChannelInboundEvent",
    "NormalizedChannelInboundTurn",
    "NormalizedChannelInboundTurnResult",
    "ExtensionError",
    "TraceContext",
    "IdempotencyKey",
    "ExternalSecretRef"
  ];
  for (const schemaName of requiredSchemas) {
    assert(hasOwn(openApi.components.schemas, schemaName), `OpenAPI missing component schema ${schemaName}`);
  }

  assertNormalizedEventAcceptedOpenApi(openApi);
  assertMessageClassInboundUsesTurnOpenApi(openApi);

  const descriptorPaths = [
    "/tools/invoke",
    "/channel/run-job",
    "/internal/channel-events/normalized",
    "/internal/channel-turns/normalized"
  ];
  for (const descriptorPath of descriptorPaths) {
    const headers = operationHeaders(openApi, descriptorPath, "post");
    for (const header of [
      "Authorization",
      "X-Lynxus-Extension-Registration-Id",
      "X-Lynxus-Extension-Descriptor-Type",
      "X-Lynxus-Extension-Descriptor-Id",
      "X-Lynxus-Trace-Id",
      "X-Lynxus-Request-Id",
      "Idempotency-Key"
    ]) {
      assert(headers.includes(header), `${descriptorPath} must declare ${header}`);
    }
  }

  for (const credentialPath of ["/credentials", "/credentials/rotate", "/credentials/revoke", "/credentials/validate"]) {
    const headers = operationHeaders(openApi, credentialPath, "post");
    assert(headers.includes("Authorization"), `${credentialPath} must declare Authorization`);
    assert(headers.includes("X-Lynxus-Trace-Id"), `${credentialPath} must declare X-Lynxus-Trace-Id`);
    assert(headers.includes("X-Lynxus-Request-Id"), `${credentialPath} must declare X-Lynxus-Request-Id`);
    assert(!headers.includes("Idempotency-Key"), `${credentialPath} must not declare Idempotency-Key`);
    assert(!headers.some((header) => header.startsWith("X-Lynxus-Extension-")), `${credentialPath} must not use extension descriptor headers`);
  }

  const manifestHeaders = operationHeaders(openApi, "/extension/manifest", "get");
  assert(manifestHeaders.includes("Authorization"), "/extension/manifest must declare Authorization");
  assert(!manifestHeaders.includes("X-Lynxus-Trace-Id"), "/extension/manifest must not require trace headers");
  assert(!manifestHeaders.includes("Idempotency-Key"), "/extension/manifest must not require idempotency");

  assertDescriptorNonEmptyConstraint(openApi.components.schemas.ServiceManifestEnvelope.properties.descriptors, "OpenAPI ServiceManifestEnvelope.descriptors");
  assertCredentialLifecycleOpenApi(openApi);
}

function assertNormalizedEventAcceptedOpenApi(openApi) {
  const accepted = openApi.components.schemas.NormalizedEventAccepted?.properties?.accepted;
  assert(accepted?.const === true, "OpenAPI NormalizedEventAccepted.accepted must use const: true");
}

function assertMessageClassInboundUsesTurnOpenApi(openApi) {
  const eventTypes = openApi.components.schemas.NormalizedChannelInboundEvent?.properties?.eventType?.enum ?? [];
  assert(!eventTypes.includes("MESSAGE_RECEIVED"), "OpenAPI NormalizedChannelInboundEvent must not accept MESSAGE_RECEIVED");
  assert(!eventTypes.includes("FILE_RECEIVED"), "OpenAPI NormalizedChannelInboundEvent must not accept FILE_RECEIVED");
  const turnMessages = openApi.components.schemas.NormalizedChannelInboundTurn?.properties?.messages?.items?.$ref;
  assert(turnMessages === "#/components/schemas/NormalizedChannelTurnMessage", "OpenAPI message-class inbound must use NormalizedChannelInboundTurn.messages");
}

function operationHeaders(openApi, pathName, method) {
  const operation = openApi.paths[pathName]?.[method];
  assert(operation, `OpenAPI path ${pathName} missing ${method}`);
  return (operation.parameters ?? []).map((parameter) => {
    if (parameter.$ref) {
      const name = parameter.$ref.split("/").at(-1);
      return openApi.components.parameters[name].name;
    }
    return parameter.name;
  });
}

function operationRequestSchemaRef(openApi, pathName, method) {
  const operation = openApi.paths[pathName]?.[method];
  assert(operation, `OpenAPI path ${pathName} missing ${method}`);
  return operation.requestBody?.content?.["application/json"]?.schema?.$ref?.split("/").at(-1);
}

function operationResponseSchemaRef(openApi, pathName, method, status) {
  const operation = openApi.paths[pathName]?.[method];
  assert(operation, `OpenAPI path ${pathName} missing ${method}`);
  return operation.responses?.[status]?.content?.["application/json"]?.schema?.$ref?.split("/").at(-1);
}

function assertSchemaRef(schema, expectedRef, label) {
  assert(schema?.$ref === `#/components/schemas/${expectedRef}`, `${label} must reference ${expectedRef}`);
}

function assertRequiredFields(schema, fields, label) {
  for (const field of fields) {
    assert(schema.required?.includes(field), `${label} must require ${field}`);
  }
}

function assertNoProperty(schema, propertyName, label) {
  assert(!hasOwn(schema.properties ?? {}, propertyName), `${label} must not declare ${propertyName}`);
}

function assertCredentialLifecycleOpenApi(openApi) {
  const expectedRequestRefs = {
    "/credentials": "CreateCredentialRequest",
    "/credentials/rotate": "RotateCredentialRequest",
    "/credentials/validate": "ValidateCredentialRequest",
    "/credentials/revoke": "RevokeCredentialRequest"
  };
  const expectedResponseRefs = {
    "/credentials": "CreateCredentialResponse",
    "/credentials/rotate": "RotateCredentialResponse",
    "/credentials/validate": "ValidateCredentialResponse",
    "/credentials/revoke": "RevokeCredentialResponse"
  };
  for (const [pathName, schemaName] of Object.entries(expectedRequestRefs)) {
    assert(operationRequestSchemaRef(openApi, pathName, "post") === schemaName, `${pathName} request must use ${schemaName}`);
  }
  for (const [pathName, schemaName] of Object.entries(expectedResponseRefs)) {
    assert(operationResponseSchemaRef(openApi, pathName, "post", "200") === schemaName, `${pathName} response must use ${schemaName}`);
  }

  const schemas = openApi.components.schemas;
  assertRequiredFields(schemas.CreateCredentialAccount, ["config"], "CreateCredentialAccount");
  assertNoProperty(schemas.CreateCredentialAccount, "externalSecretRef", "CreateCredentialAccount");
  assertRequiredFields(schemas.ExistingCredentialAccount, ["config", "externalSecretRef"], "ExistingCredentialAccount");

  assertSchemaRef(schemas.CreateCredentialRequest.properties.account, "CreateCredentialAccount", "CreateCredentialRequest.account");
  assertRequiredFields(schemas.CreateCredentialRequest, ["descriptor", "account", "credential", "traceContext"], "CreateCredentialRequest");
  assertSchemaRef(schemas.RotateCredentialRequest.properties.account, "ExistingCredentialAccount", "RotateCredentialRequest.account");
  assertRequiredFields(schemas.RotateCredentialRequest, ["descriptor", "account", "credential", "traceContext"], "RotateCredentialRequest");
  assertSchemaRef(schemas.ValidateCredentialRequest.properties.account, "ExistingCredentialAccount", "ValidateCredentialRequest.account");
  assertRequiredFields(schemas.ValidateCredentialRequest, ["descriptor", "account", "traceContext"], "ValidateCredentialRequest");
  assertNoProperty(schemas.ValidateCredentialRequest, "credential", "ValidateCredentialRequest");
  assertSchemaRef(schemas.RevokeCredentialRequest.properties.account, "ExistingCredentialAccount", "RevokeCredentialRequest.account");
  assertRequiredFields(schemas.RevokeCredentialRequest, ["descriptor", "account", "traceContext"], "RevokeCredentialRequest");
  assertNoProperty(schemas.RevokeCredentialRequest, "credential", "RevokeCredentialRequest");

  assertCredentialResponseSchema(schemas.CreateCredentialResponse, "CreateCredentialResponse", ["ACTIVE"], true);
  assertCredentialResponseSchema(schemas.RotateCredentialResponse, "RotateCredentialResponse", ["ACTIVE"], true);
  assertCredentialResponseSchema(schemas.ValidateCredentialResponse, "ValidateCredentialResponse", ["ACTIVE", "VALIDATION_FAILED", "ROTATION_REQUIRED"], false);
  assertCredentialResponseSchema(schemas.RevokeCredentialResponse, "RevokeCredentialResponse", ["REVOKED"], false);
}

function assertCredentialResponseSchema(schema, label, expectedStatuses, requiresExternalSecretRef) {
  assertRequiredFields(schema, ["credentialStatus"], label);
  if (requiresExternalSecretRef) {
    assertRequiredFields(schema, ["externalSecretRef"], label);
  } else {
    assertNoProperty(schema, "externalSecretRef", label);
  }
  const statusSchema = schema.properties?.credentialStatus;
  const statuses = hasOwn(statusSchema ?? {}, "const") ? [statusSchema.const] : statusSchema?.enum;
  assert(Array.isArray(statuses), `${label}.credentialStatus must use const or enum`);
  assert(sameStringValues(statuses, expectedStatuses), `${label}.credentialStatus must be ${expectedStatuses.join(", ")}`);
}

function assertDescriptorNonEmptyConstraint(schema, label) {
  const branches = schema?.anyOf;
  assert(Array.isArray(branches), `${label} must encode non-empty descriptor collections with anyOf`);
  const channelBranch = branches.some((branch) => branch.properties?.channelProviders?.minItems === 1);
  const toolBranch = branches.some((branch) => branch.properties?.toolConnectors?.minItems === 1);
  assert(channelBranch && toolBranch, `${label} must require channelProviders or toolConnectors to be non-empty`);
}

function sameStringValues(left, right) {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}

function checkSchemaAssets() {
  const schemaFiles = listJsonFiles(path.join(packageRoot, "json-schema"));
  for (const file of schemaFiles) {
    const schema = readJsonFile(file);
    assert(schema.$schema === "https://json-schema.org/draft/2020-12/schema", `${relative(file)} must use Draft 2020-12`);
    assert(typeof schema.$id === "string" && schema.$id.length > 0, `${relative(file)} must declare $id`);
    assert(typeof schema.title === "string" && schema.title.length > 0, `${relative(file)} must declare title`);
    if (path.basename(file) === "service-manifest.schema.json") {
      assertDescriptorNonEmptyConstraint(schema.properties?.descriptors, "JSON Schema service-manifest.descriptors");
    }
  }
  return schemaFiles.length;
}

function checkExamples() {
  let count = 0;
  for (const file of listJsonFiles(path.join(packageRoot, "examples"))) {
    const example = readJsonFile(file);
    if (file.includes("service-manifest.")) {
      expectNoManifestErrors(file, validateManifest(example));
      count += 1;
    } else if (file.includes("extension-error.")) {
      validateExtensionErrorExample(file, example);
      count += 1;
    } else if (file.includes("assistant-binding.")) {
      assert(typeof example.assistantId === "string", `${relative(file)} must include assistantId`);
      count += 1;
    } else if (file.includes("schedule-config.")) {
      assert(["INTERVAL", "CRON", "MANUAL"].includes(example.scheduleType), `${relative(file)} invalid scheduleType`);
      assert(isObject(example.jobConfig), `${relative(file)} must include jobConfig object`);
      count += 1;
    }
  }
  return count;
}

function validateExtensionErrorExample(file, example) {
  for (const field of ["errorCode", "message", "category", "retryable", "details"]) {
    assert(hasOwn(example, field), `${relative(file)} missing ExtensionError.${field}`);
  }
  assert(isObject(example.details), `${relative(file)} ExtensionError.details must be object`);
}

function checkManifestFixtures() {
  const validDir = path.join(packageRoot, "contract-tests", "fixtures", "manifest-valid");
  const invalidDir = path.join(packageRoot, "contract-tests", "fixtures", "manifest-invalid");
  let count = 0;

  for (const file of listJsonFiles(validDir)) {
    const fixture = readJsonFile(file);
    assert(fixture.expectedErrorCode === null, `${relative(file)} valid fixture must set expectedErrorCode to null`);
    expectNoManifestErrors(file, validateManifest(fixture.manifest));
    count += 1;
  }

  for (const file of listJsonFiles(invalidDir)) {
    const fixture = readJsonFile(file);
    assert(typeof fixture.expectedErrorCode === "string", `${relative(file)} invalid fixture must set expectedErrorCode`);
    const errors = validateManifest(fixture.manifest);
    const codes = errors.map((error) => error.code);
    assert(codes.includes(fixture.expectedErrorCode), `${relative(file)} expected ${fixture.expectedErrorCode}, got ${codes.join(", ") || "no errors"}`);
    count += 1;
  }

  return count;
}

function expectNoManifestErrors(file, errors) {
  assert(errors.length === 0, `${relative(file)} manifest errors: ${errors.map((error) => `${error.code}@${error.path}`).join(", ")}`);
}

function validateManifest(manifest) {
  const errors = [];
  if (!isObject(manifest)) {
    return [{ code: "MANIFEST_INVALID", path: "", message: "Manifest must be object" }];
  }
  if (manifest.extensionApiVersion !== 1) {
    errors.push({ code: "MANIFEST_INVALID", path: "/extensionApiVersion", message: "extensionApiVersion must be 1" });
  }
  if (!isObject(manifest.descriptors)) {
    errors.push({ code: "MANIFEST_INVALID", path: "/descriptors", message: "descriptors must be object" });
    return errors;
  }
  const channelProviders = manifest.descriptors.channelProviders;
  const toolConnectors = manifest.descriptors.toolConnectors;
  if (!Array.isArray(channelProviders) || !Array.isArray(toolConnectors)) {
    errors.push({ code: "MANIFEST_INVALID", path: "/descriptors", message: "descriptor collections must be arrays" });
    return errors;
  }
  if (channelProviders.length === 0 && toolConnectors.length === 0) {
    errors.push({ code: "MANIFEST_EMPTY", path: "/descriptors", message: "manifest must expose at least one descriptor" });
  }
  const credentialLifecycleEndpointProfiles = manifest.credentialLifecycleEndpointProfiles ?? {};
  validateCredentialLifecycleEndpointProfiles(credentialLifecycleEndpointProfiles, errors);
  channelProviders.forEach((descriptor, index) => validateChannelProvider(descriptor, `/descriptors/channelProviders/${index}`, credentialLifecycleEndpointProfiles, errors));
  toolConnectors.forEach((descriptor, index) => validateToolConnector(descriptor, `/descriptors/toolConnectors/${index}`, credentialLifecycleEndpointProfiles, errors));
  return errors;
}

function validateChannelProvider(descriptor, pathPrefix, credentialLifecycleEndpointProfiles, errors) {
  requireFields(descriptor, ["providerType", "title", "accountConfigSchema", "accountConfigUiSchema", "configSchema", "configUiSchema", "outbound", "endpoints"], pathPrefix, errors);
  validateChannelProviderOutbound(descriptor.outbound, `${pathPrefix}/outbound`, errors);
  validateCredentialCapability(descriptor, pathPrefix, credentialLifecycleEndpointProfiles, errors);
  validateUiPair(descriptor.accountConfigSchema, descriptor.accountConfigUiSchema ?? [], `${pathPrefix}/accountConfigUiSchema`, false, errors);
  validateUiPair(descriptor.configSchema, descriptor.configUiSchema ?? [], `${pathPrefix}/configUiSchema`, false, errors);
  scanNormalConfigForSecrets(descriptor.configSchema, `${pathPrefix}/configSchema`, errors);
  scanNormalConfigForSecrets(descriptor.defaultConfig, `${pathPrefix}/defaultConfig`, errors);
  if (hasOwn(descriptor, "credentialUiSchema") || hasOwn(descriptor, "credentialSchema")) {
    validateUiPair(descriptor.credentialSchema ?? { type: "object", properties: {} }, descriptor.credentialUiSchema ?? [], `${pathPrefix}/credentialUiSchema`, true, errors);
  }

  const jobDefinitions = descriptor.jobDefinitions ?? [];
  if (!Array.isArray(jobDefinitions)) {
    errors.push({ code: "MANIFEST_INVALID", path: `${pathPrefix}/jobDefinitions`, message: "jobDefinitions must be an array" });
    return;
  }
  if (jobDefinitions.length > 0) {
    validateDeclaredPath(descriptor.endpoints?.runJob, `${pathPrefix}/endpoints/runJob`, errors);
  } else if (hasOwn(descriptor.endpoints ?? {}, "runJob")) {
    errors.push({ code: "MANIFEST_ENDPOINT_INVALID", path: `${pathPrefix}/endpoints/runJob`, message: "runJob requires jobDefinitions" });
  }
  const jobTypes = new Set();
  jobDefinitions.forEach((job, index) => {
    const jobPath = `${pathPrefix}/jobDefinitions/${index}`;
    requireFields(job, ["jobType", "title", "jobConfigSchema", "jobConfigUiSchema"], jobPath, errors);
    if (jobTypes.has(job.jobType)) {
      errors.push({ code: "MANIFEST_DUPLICATE_JOB_TYPE", path: `${jobPath}/jobType`, message: "duplicate jobType" });
    }
    jobTypes.add(job.jobType);
    validateUiPair(job.jobConfigSchema, job.jobConfigUiSchema ?? [], `${jobPath}/jobConfigUiSchema`, false, errors);
    scanNormalConfigForSecrets(job.jobConfigSchema, `${jobPath}/jobConfigSchema`, errors);
    scanNormalConfigForSecrets(job.defaultSchedule?.jobConfig, `${jobPath}/defaultSchedule/jobConfig`, errors);
  });
}

function validateChannelProviderOutbound(outbound, pathPrefix, errors) {
  if (!isObject(outbound)) {
    errors.push({ code: "MANIFEST_INVALID", path: pathPrefix, message: "outbound must be object" });
    return;
  }
  requireFields(
    outbound,
    [
      "mode",
      "supportsTyping",
      "supportsDraftUpdate",
      "supportsFinalDelivery",
      "requiresIdempotentFinalDelivery"
    ],
    pathPrefix,
    errors
  );
  if (outbound.mode !== "FRAME_STREAM") {
    errors.push({ code: "MANIFEST_INVALID", path: `${pathPrefix}/mode`, message: "outbound.mode must be FRAME_STREAM" });
  }
  if (outbound.supportsFinalDelivery !== true) {
    errors.push({
      code: "MANIFEST_INVALID",
      path: `${pathPrefix}/supportsFinalDelivery`,
      message: "outbound.supportsFinalDelivery must be true"
    });
  }
  if (outbound.requiresIdempotentFinalDelivery !== true) {
    errors.push({
      code: "MANIFEST_INVALID",
      path: `${pathPrefix}/requiresIdempotentFinalDelivery`,
      message: "outbound.requiresIdempotentFinalDelivery must be true"
    });
  }
}

function validateToolConnector(descriptor, pathPrefix, credentialLifecycleEndpointProfiles, errors) {
  requireFields(descriptor, ["connectorType", "title", "accountConfigSchema", "accountConfigUiSchema", "configSchema", "configUiSchema", "operationMappingSchema", "operationMappingUiSchema", "endpoints"], pathPrefix, errors);
  validateDeclaredPath(descriptor.endpoints?.invoke, `${pathPrefix}/endpoints/invoke`, errors);
  validateCredentialCapability(descriptor, pathPrefix, credentialLifecycleEndpointProfiles, errors);
  validateUiPair(descriptor.accountConfigSchema, descriptor.accountConfigUiSchema ?? [], `${pathPrefix}/accountConfigUiSchema`, false, errors);
  validateUiPair(descriptor.configSchema, descriptor.configUiSchema ?? [], `${pathPrefix}/configUiSchema`, false, errors);
  validateUiPair(descriptor.operationMappingSchema, descriptor.operationMappingUiSchema ?? [], `${pathPrefix}/operationMappingUiSchema`, false, errors);
  scanNormalConfigForSecrets(descriptor.configSchema, `${pathPrefix}/configSchema`, errors);
  scanNormalConfigForSecrets(descriptor.operationMappingSchema, `${pathPrefix}/operationMappingSchema`, errors);
  if (hasOwn(descriptor, "credentialUiSchema") || hasOwn(descriptor, "credentialSchema")) {
    validateUiPair(descriptor.credentialSchema ?? { type: "object", properties: {} }, descriptor.credentialUiSchema ?? [], `${pathPrefix}/credentialUiSchema`, true, errors);
  }
}

function requireFields(object, fields, pathPrefix, errors) {
  if (!isObject(object)) {
    errors.push({ code: "MANIFEST_INVALID", path: pathPrefix, message: "expected object" });
    return;
  }
  for (const field of fields) {
    if (!hasOwn(object, field)) {
      errors.push({ code: "MANIFEST_REQUIRED_FIELD_MISSING", path: `${pathPrefix}/${field}`, message: "required field missing" });
    }
  }
}

function validateDeclaredPath(value, fieldPath, errors) {
  if (typeof value !== "string" || !value.startsWith("/") || value.includes("?") || value.includes("#") || /^[a-z][a-z0-9+.-]*:\/\//i.test(value)) {
    errors.push({ code: "MANIFEST_ENDPOINT_INVALID", path: fieldPath, message: "endpoint must be a declared relative path" });
  }
}

function validateCredentialLifecycleEndpointProfiles(profiles, errors) {
  if (!isObject(profiles)) {
    errors.push({ code: "MANIFEST_INVALID", path: "/credentialLifecycleEndpointProfiles", message: "credentialLifecycleEndpointProfiles must be object" });
    return;
  }
  const requiredCredentialKeys = ["createCredential", "rotateCredential", "revokeCredential"];
  const credentialKeys = [...requiredCredentialKeys, "validateCredential"];
  for (const [profileName, endpoints] of Object.entries(profiles)) {
    const profilePath = `/credentialLifecycleEndpointProfiles/${profileName}`;
    if (!isObject(endpoints)) {
      errors.push({ code: "MANIFEST_INVALID", path: profilePath, message: "credential lifecycle endpoint profile must be object" });
      continue;
    }
    if (!requiredCredentialKeys.every((key) => hasOwn(endpoints, key))) {
      errors.push({ code: "CREDENTIAL_ENDPOINTS_INCOMPLETE", path: profilePath, message: "credential lifecycle endpoint profiles require createCredential, rotateCredential, and revokeCredential; validateCredential is optional" });
    }
    for (const key of credentialKeys) {
      if (hasOwn(endpoints, key)) {
        validateDeclaredPath(endpoints[key], `${profilePath}/${key}`, errors);
      }
    }
  }
}

function validateCredentialCapability(descriptor, pathPrefix, credentialLifecycleEndpointProfiles, errors) {
  const profile = descriptor.credentialLifecycleEndpointProfile;
  if (profile === undefined) {
    return;
  }
  if (typeof profile !== "string" || profile.length === 0) {
    errors.push({ code: "MANIFEST_INVALID", path: `${pathPrefix}/credentialLifecycleEndpointProfile`, message: "credentialLifecycleEndpointProfile must be non-empty string" });
    return;
  }
  if (!isObject(descriptor.credentialSchema)) {
    errors.push({ code: "CREDENTIAL_ENDPOINT_PROFILE_INVALID", path: `${pathPrefix}/credentialLifecycleEndpointProfile`, message: "credentialLifecycleEndpointProfile requires credentialSchema" });
  }
  if (!hasOwn(credentialLifecycleEndpointProfiles, profile)) {
    errors.push({ code: "CREDENTIAL_ENDPOINT_PROFILE_INVALID", path: `${pathPrefix}/credentialLifecycleEndpointProfile`, message: "credentialLifecycleEndpointProfile must reference credentialLifecycleEndpointProfiles" });
  }
}

function validateUiPair(dataSchema, uiSchema, pathPrefix, secretAllowed, errors) {
  if (!Array.isArray(uiSchema)) {
    errors.push({ code: "UI_SCHEMA_INVALID", path: pathPrefix, message: "UI schema must be an array" });
    return;
  }
  const keys = new Set();
  uiSchema.forEach((field, index) => {
    const fieldPath = `${pathPrefix}/${index}`;
    if (!isObject(field)) {
      errors.push({ code: "UI_SCHEMA_INVALID", path: fieldPath, message: "UI field must be object" });
      return;
    }
    if (keys.has(field.key)) {
      errors.push({ code: "UI_SCHEMA_DUPLICATE_KEY", path: `${fieldPath}/key`, message: "duplicate UI key" });
    }
    keys.add(field.key);

    const resolved = resolveSchemaProperty(dataSchema, field.key);
    if (!resolved) {
      errors.push({ code: "UI_SCHEMA_PROPERTY_NOT_FOUND", path: `${fieldPath}/key`, message: "UI key does not resolve to JSON Schema property" });
      return;
    }
    if (!secretAllowed && (field.secret === true || field.component === "password")) {
      errors.push({ code: "UI_SCHEMA_SECRET_NOT_ALLOWED", path: fieldPath, message: "secret inputs are only allowed for credentialUiSchema" });
    }
    if (field.required === true && !isRequiredByParent(resolved.parentSchema, resolved.propertyName)) {
      errors.push({ code: "UI_SCHEMA_REQUIRED_NOT_AUTHORITATIVE", path: `${fieldPath}/required`, message: "UI required cannot replace JSON Schema required" });
    }
    if (field.visibilityCondition) {
      if (!resolveSchemaProperty(dataSchema, field.visibilityCondition.field)) {
        errors.push({ code: "UI_SCHEMA_PROPERTY_NOT_FOUND", path: `${fieldPath}/visibilityCondition/field`, message: "visibility field does not resolve to JSON Schema property" });
      }
      if (isRequiredByParent(resolved.parentSchema, resolved.propertyName)) {
        errors.push({ code: "UI_SCHEMA_VISIBILITY_REQUIRED_CONFLICT", path: `${fieldPath}/visibilityCondition`, message: "hidden field is unconditionally required by JSON Schema" });
      }
      if (["in", "notIn"].includes(field.visibilityCondition.operator) && (!Array.isArray(field.visibilityCondition.value) || field.visibilityCondition.value.length === 0)) {
        errors.push({ code: "UI_SCHEMA_INVALID", path: `${fieldPath}/visibilityCondition/value`, message: "in/notIn requires a non-empty array value" });
      }
    }
    validateUiOptions(field, resolved.propertySchema, fieldPath, errors);
  });
}

function resolveSchemaProperty(schema, pointer) {
  if (typeof pointer !== "string" || !pointer.startsWith("/")) {
    return null;
  }
  const segments = pointer.slice(1).split("/").map((segment) => segment.replaceAll("~1", "/").replaceAll("~0", "~"));
  let current = schema;
  let parentSchema = null;
  let propertyName = null;
  for (const segment of segments) {
    if (!isObject(current) || !isObject(current.properties) || !hasOwn(current.properties, segment)) {
      return null;
    }
    parentSchema = current;
    propertyName = segment;
    current = current.properties[segment];
  }
  return { parentSchema, propertyName, propertySchema: current };
}

function isRequiredByParent(parentSchema, propertyName) {
  return Array.isArray(parentSchema?.required) && parentSchema.required.includes(propertyName);
}

function validateUiOptions(field, propertySchema, fieldPath, errors) {
  if (!["select", "radio", "checkboxGroup", "multiSelect"].includes(field.component) || !hasOwn(field, "options")) {
    return;
  }
  const schemaOptions = staticSchemaOptions(propertySchema);
  const uiOptions = field.options.map((option) => option.value);
  if (!schemaOptions || !sameJsonValues(schemaOptions, uiOptions)) {
    errors.push({ code: "UI_SCHEMA_OPTIONS_DRIFT", path: `${fieldPath}/options`, message: "UI options must match JSON Schema enum/const options" });
  }
}

function staticSchemaOptions(propertySchema) {
  if (Array.isArray(propertySchema?.enum)) {
    return propertySchema.enum;
  }
  if (Array.isArray(propertySchema?.oneOf)) {
    const values = [];
    for (const item of propertySchema.oneOf) {
      if (!hasOwn(item, "const")) return null;
      values.push(item.const);
    }
    return values;
  }
  return null;
}

function sameJsonValues(left, right) {
  if (left.length !== right.length) return false;
  return left.every((item, index) => canonicalString(item) === canonicalString(right[index]));
}

function scanNormalConfigForSecrets(value, pathPrefix, errors) {
  if (!isObject(value) && !Array.isArray(value)) return;
  if (isObject(value) && value.secret === true) {
    errors.push({ code: "CONFIG_SECRET_MATERIAL_NOT_ALLOWED", path: `${pathPrefix}/secret`, message: "secret=true is not allowed in normal config schema/default" });
  }
  if (isObject(value.properties)) {
    for (const key of Object.keys(value.properties)) {
      if (["password", "apiKey", "accessToken", "refreshToken", "privateKey", "externalSecretRef", "webhookSigningSecret"].includes(key)) {
        errors.push({ code: "CONFIG_SECRET_MATERIAL_NOT_ALLOWED", path: `${pathPrefix}/properties/${key}`, message: "normal config schema must not define secret material" });
      }
    }
  }
  const entries = Array.isArray(value) ? value.entries() : Object.entries(value);
  for (const [key, child] of entries) {
    scanNormalConfigForSecrets(child, `${pathPrefix}/${key}`, errors);
  }
}

function checkRequestEnvelopeFixtures() {
  let count = 0;
  for (const file of listJsonFiles(path.join(packageRoot, "contract-tests", "fixtures", "request-envelope"))) {
    const fixture = readJsonFile(file);
    const errors = validateRequestEnvelopeFixture(fixture);
    if (fixture.expectedErrorCode === null) {
      assert(errors.length === 0, `${relative(file)} request envelope errors: ${errors.map((error) => error.code).join(", ")}`);
    } else {
      assert(errors.map((error) => error.code).includes(fixture.expectedErrorCode), `${relative(file)} expected ${fixture.expectedErrorCode}, got ${errors.map((error) => error.code).join(", ") || "no errors"}`);
    }
    count += 1;
  }
  return count;
}

function validateRequestEnvelopeFixture(fixture) {
  const errors = [];
  const headers = fixture.headers ?? {};
  const request = fixture.request ?? {};

  const descriptorOperations = new Set(["toolInvoke", "channelRunJob", "normalizedEvent"]);
  const credentialOperations = new Set(["createCredential", "rotateCredential", "validateCredential", "revokeCredential"]);
  const serviceOperations = new Set(["serviceManifest", "extensionHealth", "healthLive", "healthReady"]);
  const authRequiredOperations = new Set([...descriptorOperations, ...credentialOperations, "serviceManifest", "extensionHealth"]);

  if (authRequiredOperations.has(fixture.operation) && !headers.Authorization) {
    errors.push({ code: "AUTH_HEADER_REQUIRED" });
  }

  if (descriptorOperations.has(fixture.operation)) {
    for (const header of [
      "X-Lynxus-Extension-Registration-Id",
      "X-Lynxus-Extension-Descriptor-Type",
      "X-Lynxus-Extension-Descriptor-Id",
      "X-Lynxus-Trace-Id",
      "X-Lynxus-Request-Id",
      "Idempotency-Key"
    ]) {
      if (!headers[header]) errors.push({ code: "REQUIRED_HEADER_MISSING" });
    }
    const traceContext = descriptorTraceContext(fixture.operation, request);
    validateTrace(headers, traceContext, errors);
    validateIdempotency(fixture.operation, headers, request, errors);
  } else if (credentialOperations.has(fixture.operation)) {
    if (!headers["X-Lynxus-Trace-Id"] || !headers["X-Lynxus-Request-Id"]) {
      errors.push({ code: "REQUIRED_HEADER_MISSING" });
    }
    for (const header of Object.keys(headers)) {
      if (header.startsWith("X-Lynxus-Extension-")) {
        errors.push({ code: "DESCRIPTOR_HEADER_NOT_ALLOWED" });
      }
    }
    validateTrace(headers, request.traceContext, errors);
    if (headers["Idempotency-Key"] || hasOwn(request, "idempotencyKey")) {
      errors.push({ code: "IDEMPOTENCY_NOT_ALLOWED" });
    }
    validateCredentialRequest(fixture.operation, request, errors);
    if (hasOwn(fixture, "response")) {
      validateCredentialResponse(fixture.operation, fixture.response, errors);
    }
  } else if (serviceOperations.has(fixture.operation)) {
    if (headers["Idempotency-Key"]) {
      errors.push({ code: "IDEMPOTENCY_NOT_ALLOWED" });
    }
  } else {
    errors.push({ code: "UNKNOWN_OPERATION" });
  }
  return errors;
}

function validateCredentialRequest(operation, request, errors) {
  if (!isObject(request.account)) {
    errors.push({ code: "CREDENTIAL_ACCOUNT_REQUIRED" });
    return;
  }
  if (!isObject(request.account.config)) {
    errors.push({ code: "CREDENTIAL_ACCOUNT_CONFIG_REQUIRED" });
  }

  if (operation === "createCredential") {
    if (hasOwn(request.account, "externalSecretRef")) {
      errors.push({ code: "CREDENTIAL_EXTERNAL_SECRET_REF_NOT_ALLOWED" });
    }
    if (!isObject(request.credential)) {
      errors.push({ code: "CREDENTIAL_REQUIRED" });
    }
    return;
  }

  if (typeof request.account.externalSecretRef !== "string" || request.account.externalSecretRef.length === 0) {
    errors.push({ code: "CREDENTIAL_EXTERNAL_SECRET_REF_REQUIRED" });
  }
  if (operation === "rotateCredential") {
    if (!isObject(request.credential)) {
      errors.push({ code: "CREDENTIAL_REQUIRED" });
    }
  } else if (hasOwn(request, "credential")) {
    errors.push({ code: "CREDENTIAL_NOT_ALLOWED" });
  }
}

function validateCredentialResponse(operation, response, errors) {
  if (!isObject(response)) {
    errors.push({ code: "CREDENTIAL_RESPONSE_INVALID" });
    return;
  }

  if (operation === "createCredential" || operation === "rotateCredential") {
    if (typeof response.externalSecretRef !== "string" || response.externalSecretRef.length === 0) {
      errors.push({ code: "CREDENTIAL_EXTERNAL_SECRET_REF_REQUIRED" });
    }
    if (response.credentialStatus !== "ACTIVE") {
      errors.push({ code: "CREDENTIAL_STATUS_INVALID" });
    }
    return;
  }

  if (hasOwn(response, "externalSecretRef")) {
    errors.push({ code: "CREDENTIAL_EXTERNAL_SECRET_REF_NOT_ALLOWED" });
  }
  if (operation === "validateCredential") {
    if (!["ACTIVE", "VALIDATION_FAILED", "ROTATION_REQUIRED"].includes(response.credentialStatus)) {
      errors.push({ code: "CREDENTIAL_STATUS_INVALID" });
    }
  } else if (response.credentialStatus !== "REVOKED") {
    errors.push({ code: "CREDENTIAL_STATUS_INVALID" });
  }
}

function descriptorTraceContext(operation, request) {
  if (operation === "toolInvoke") {
    return request.execution?.traceContext;
  }
  return request.traceContext;
}

function validateTrace(headers, traceContext, errors) {
  if (!isObject(traceContext) || typeof traceContext.traceparent !== "string" || traceContext.traceparent.length === 0) {
    errors.push({ code: "TRACE_CONTEXT_REQUIRED" });
    return;
  }
  const traceId = traceContext.traceparent.split("-")[1];
  if (headers["X-Lynxus-Trace-Id"] && traceId && headers["X-Lynxus-Trace-Id"] !== traceId) {
    errors.push({ code: "TRACE_HEADER_MISMATCH" });
  }
}

function validateIdempotency(operation, headers, request, errors) {
  const headerKey = headers["Idempotency-Key"];
  if (!idempotencyPattern.test(headerKey ?? "")) {
    errors.push({ code: "IDEMPOTENCY_KEY_INVALID" });
    return;
  }
  let bodyKey;
  if (operation === "toolInvoke") {
    bodyKey = request.execution?.idempotencyKey;
  } else if (operation === "normalizedEvent") {
    bodyKey = request.dedupKey;
  } else {
    bodyKey = request.idempotencyKey;
  }
  if (headerKey !== bodyKey) {
    errors.push({ code: "IDEMPOTENCY_KEY_MISMATCH" });
  }
}

function checkCanonicalJsonFixtures() {
  let count = 0;
  for (const file of listJsonFiles(path.join(packageRoot, "contract-tests", "fixtures", "canonical-json"))) {
    const fixture = readJsonFile(file);
    const result = evaluateCanonicalFixture(fixture);
    if (fixture.expectedErrorCode) {
      assert(result.errorCode === fixture.expectedErrorCode, `${relative(file)} expected ${fixture.expectedErrorCode}, got ${result.errorCode ?? "no error"}`);
    } else {
      assert(!result.errorCode, `${relative(file)} unexpected canonical error ${result.errorCode}`);
      assert(fixture.expectedCanonicalUtf8Hex === result.expectedCanonicalUtf8Hex, `${relative(file)} canonical hex mismatch: expected ${fixture.expectedCanonicalUtf8Hex}, got ${result.expectedCanonicalUtf8Hex}`);
      assert(fixture.expectedDigest === result.expectedDigest, `${relative(file)} digest mismatch: expected ${fixture.expectedDigest}, got ${result.expectedDigest}`);
      if (fixture.expectedNormalizedCanonicalUtf8Hex) {
        assert(fixture.expectedNormalizedCanonicalUtf8Hex === result.expectedCanonicalUtf8Hex, `${relative(file)} normalized canonical hex mismatch`);
      }
    }
    count += 1;
  }
  return count;
}

function checkRegistrationLoaderFixtures() {
  const fixturesDir = path.join(packageRoot, "contract-tests", "fixtures", "registration-loader");
  let count = 0;
  for (const file of listJsonFiles(fixturesDir)) {
    const fixture = readJsonFile(file);
    assert(typeof fixture.name === "string" && fixture.name.length > 0, `${relative(file)} must declare a name`);
    assert(typeof fixture.operatorYaml === "string", `${relative(file)} must include operatorYaml`);
    assert(isObject(fixture.environment), `${relative(file)} must include environment object`);
    if (fixture.expectedErrorCode === null) {
      assert(isObject(fixture.expected), `${relative(file)} valid fixture must include expected object`);
      assert(isObject(fixture.expected.canonicalInput), `${relative(file)} must include expected.canonicalInput`);
      assert(
        fixture.expected.registrationConfigDigest === sha256Digest(fixture.expected.canonicalInput),
        `${relative(file)} registrationConfigDigest mismatch`
      );
      assertRegistrationDigestInput(file, fixture.expected.canonicalInput);
    } else {
      assert(
        fixture.expectedErrorCode === "REGISTRATION_CONFIG_INVALID" ||
          fixture.expectedErrorCode === "REGISTRATION_CONFIG_UNAVAILABLE",
        `${relative(file)} invalid fixture must use a registration config error code`
      );
    }
    count += 1;
  }
  return count;
}

function assertRegistrationDigestInput(file, canonicalInput) {
  assert(Array.isArray(canonicalInput.services), `${relative(file)} canonicalInput.services must be an array`);
  const serviceIds = canonicalInput.services.map((service) => service.registrationId);
  assert(sameStringValues([...serviceIds].sort(compareUtf16), serviceIds), `${relative(file)} services must be sorted by registrationId`);
  const seenIds = new Set();
  for (const service of canonicalInput.services) {
    assert(!seenIds.has(service.registrationId), `${relative(file)} duplicate registrationId ${service.registrationId}`);
    seenIds.add(service.registrationId);
    assert(["CORE_PRESET", "OPERATOR_YAML"].includes(service.source), `${relative(file)} invalid source ${service.source}`);
    assert(typeof service.baseUrl === "string" && /^https?:\/\//.test(service.baseUrl), `${relative(file)} invalid normalized baseUrl`);
    assert(service.auth?.type === "INTERNAL_TOKEN", `${relative(file)} auth.type must be INTERNAL_TOKEN`);
    assert(!hasOwn(service.auth ?? {}, "token"), `${relative(file)} auth token must not enter digest input`);
    assertRegistrationDigestList(file, service.exposes?.channelProviderTypes, `${service.registrationId}.channelProviderTypes`);
    assertRegistrationDigestList(file, service.exposes?.toolConnectorTypes, `${service.registrationId}.toolConnectorTypes`);
  }
}

function assertRegistrationDigestList(file, values, label) {
  assert(Array.isArray(values), `${relative(file)} ${label} must be an array`);
  assert(sameStringValues([...values].sort(compareUtf16), values), `${relative(file)} ${label} must be sorted`);
  assert(new Set(values).size === values.length, `${relative(file)} ${label} must be deduplicated`);
}

function evaluateCanonicalFixture(fixture) {
  try {
    let value;
    if (fixture.type === "registrationConfigDigest") {
      value = normalizeRegistrationConfig(fixture.input);
    } else if (fixture.type === "channelProviderDefinitionDigest") {
      value = channelProviderDigestObject(fixture.input, fixture.credentialLifecycleEndpointProfiles ?? {});
    } else if (fixture.type === "toolConnectorDefinitionDigest") {
      value = toolConnectorDigestObject(fixture.input, fixture.credentialLifecycleEndpointProfiles ?? {});
    } else {
      value = parseCanonicalJson(fixture.input);
    }
    return {
      expectedCanonicalUtf8Hex: canonicalUtf8Hex(value),
      expectedDigest: sha256Digest(value)
    };
  } catch (error) {
    if (error instanceof CanonicalJsonError) {
      return { errorCode: error.code };
    }
    if (error instanceof CheckFailure) {
      return { errorCode: error.message };
    }
    throw error;
  }
}

function normalizeRegistrationConfig(input) {
  const services = [...(input.services ?? [])].map((service) => ({
    registrationId: service.registrationId,
    source: service.source,
    baseUrl: normalizeBaseUrl(service.baseUrl),
    exposes: {
      channelProviderTypes: [...(service.exposes?.channelProviderTypes ?? [])].sort(compareUtf16),
      toolConnectorTypes: [...(service.exposes?.toolConnectorTypes ?? [])].sort(compareUtf16)
    },
    auth: {
      type: service.auth?.type
    }
  }));
  services.sort((left, right) => compareUtf16(left.registrationId, right.registrationId));
  return { services };
}

function normalizeBaseUrl(value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new CheckFailure("REGISTRATION_CONFIG_INVALID");
  }
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
    throw new CheckFailure("REGISTRATION_CONFIG_INVALID");
  }
  let pathname = url.pathname;
  if (pathname === "/") {
    pathname = "";
  } else {
    pathname = pathname.replace(/\/+$/, "");
  }
  return `${url.origin}${pathname}`;
}

function channelProviderDigestObject(descriptor, credentialLifecycleEndpointProfiles = {}) {
  const jobDefinitions = [...(descriptor.jobDefinitions ?? [])]
    .sort((left, right) => compareUtf16(left.jobType, right.jobType))
    .map((job) => ({
      jobType: job.jobType,
      jobConfigSchema: validationOnlySchema(job.jobConfigSchema ?? null)
    }));
  return {
    descriptorType: "CHANNEL_PROVIDER",
    providerType: descriptor.providerType,
    accountConfigSchema: validationOnlySchema(descriptor.accountConfigSchema ?? null),
    credentialSchema: validationOnlySchema(descriptor.credentialSchema ?? null),
    outbound: {
      mode: descriptor.outbound?.mode ?? null,
      requiresIdempotentFinalDelivery: descriptor.outbound?.requiresIdempotentFinalDelivery === true,
      supportsDraftUpdate: descriptor.outbound?.supportsDraftUpdate === true,
      supportsFinalDelivery: descriptor.outbound?.supportsFinalDelivery === true,
      supportsTyping: descriptor.outbound?.supportsTyping === true
    },
    endpoints: {
      runJob: descriptor.endpoints?.runJob ?? null
    },
    credentialLifecycleEndpointProfile: descriptor.credentialLifecycleEndpointProfile ?? null,
    credentialLifecycleEndpoints: credentialLifecycleEndpoints(descriptor, credentialLifecycleEndpointProfiles),
    configSchema: validationOnlySchema(descriptor.configSchema ?? null),
    jobDefinitions
  };
}

function toolConnectorDigestObject(descriptor, credentialLifecycleEndpointProfiles = {}) {
  return {
    descriptorType: "TOOL_CONNECTOR",
    connectorType: descriptor.connectorType,
    accountConfigSchema: validationOnlySchema(descriptor.accountConfigSchema ?? null),
    credentialSchema: validationOnlySchema(descriptor.credentialSchema ?? null),
    configSchema: validationOnlySchema(descriptor.configSchema ?? null),
    operationMappingSchema: validationOnlySchema(descriptor.operationMappingSchema ?? null),
    endpoints: {
      invoke: descriptor.endpoints?.invoke ?? null
    },
    credentialLifecycleEndpointProfile: descriptor.credentialLifecycleEndpointProfile ?? null,
    credentialLifecycleEndpoints: credentialLifecycleEndpoints(descriptor, credentialLifecycleEndpointProfiles)
  };
}

function credentialLifecycleEndpoints(descriptor, credentialLifecycleEndpointProfiles) {
  const profileName = descriptor.credentialLifecycleEndpointProfile;
  const profile = typeof profileName === "string" && isObject(credentialLifecycleEndpointProfiles[profileName])
    ? credentialLifecycleEndpointProfiles[profileName]
    : {};
  return {
    createCredential: profile.createCredential ?? null,
    rotateCredential: profile.rotateCredential ?? null,
    revokeCredential: profile.revokeCredential ?? null,
    validateCredential: profile.validateCredential ?? null
  };
}

function validationOnlySchema(value, schemaMapEntries = false) {
  if (Array.isArray(value)) {
    return value.map((item) => validationOnlySchema(item));
  }
  if (!isObject(value)) {
    return value;
  }
  const next = Object.create(null);
  for (const key of Object.keys(value)) {
    if (!schemaMapEntries && schemaAnnotationKeywords.has(key)) {
      continue;
    }
    next[key] = validationOnlySchema(value[key], !schemaMapEntries && schemaMapKeywords.has(key));
  }
  return next;
}

function main() {
  const counts = {
    jsonAssets: checkJsonAssets(),
    jsonSchemas: checkSchemaAssets(),
    examples: checkExamples(),
    manifestFixtures: checkManifestFixtures(),
    requestFixtures: checkRequestEnvelopeFixtures(),
    canonicalFixtures: checkCanonicalJsonFixtures(),
    registrationLoaderFixtures: checkRegistrationLoaderFixtures()
  };
  checkOpenApi();
  console.log(`extension-protocol self-check passed: ${JSON.stringify(counts)}`);
}

try {
  main();
} catch (error) {
  if (error instanceof CheckFailure) {
    console.error(`extension-protocol self-check failed: ${error.message}`);
    process.exit(1);
  }
  console.error(error);
  process.exit(1);
}
