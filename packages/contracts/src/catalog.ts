export type Role = 'PLATFORM_ADMIN' | 'DEVELOPER' | 'BUSINESS_USER';
export type ResourceType = 'TOOL' | 'LLM_MODEL' | 'SKILL';
export type ShareScope = 'PRIVATE' | 'SHARED';
export type ResourceOwnerType = 'PERSON' | 'ORGANIZATION' | 'PLATFORM';
export type VersionStatus = 'DRAFT' | 'PUBLISHED';
export type ReferenceObjectType = 'ROLE_TEMPLATE' | 'PLAYBOOK' | 'RESOURCE' | 'KNOWLEDGE_BASE';
export type PlatformAggregateType = 'ROLE_TEMPLATE' | 'ENTRY' | 'PLAYBOOK' | 'RESOURCE' | 'KNOWLEDGE_BASE';
export type ReferenceRelationMode = 'DIRECT' | 'INDIRECT';
export type ReferenceImpactLevel = 'BLOCKS_DELETION' | 'ADVISORY';
export type KnowledgeFileStatus = 'UPLOADED' | 'IMPORTING' | 'IMPORTED' | 'FAILED';
export type KnowledgeImportJobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
export type KnowledgeImportSourceType = 'FILE_UPLOAD' | 'URL';
export type KnowledgeIndexSnapshotStatus = 'QUEUED' | 'RUNNING' | 'READY' | 'FAILED';
export type KnowledgeImportJobStage = 'QUEUED' | 'FETCHING_SOURCE' | 'PARSING' | 'CHUNKING' | 'PERSISTING' | 'SUCCEEDED' | 'FAILED';
export type KnowledgeIndexSnapshotStage = 'QUEUED' | 'COLLECTING_DOCUMENTS' | 'INDEXING' | 'READY' | 'FAILED';

export interface UserSession {
  userId: string;
  displayName: string;
  currentRole: Role;
  availableRoles: Role[];
}

export interface LogoutResponse {
  postLogoutRedirectUrl: string;
}

export interface Version {
  version: string;
  status: VersionStatus;
  updatedAt: string;
}

export interface KnowledgeRetrievalProfile {
  defaultTopK: number;
  retrievalMode: 'LEXICAL' | 'VECTOR' | 'HYBRID';
  minScore: number;
}

export type KnowledgeBaseConfig = KnowledgeRetrievalProfile;

export interface KnowledgeBindingSnapshot extends KnowledgeRetrievalProfile {
  knowledgeBaseId: string;
  knowledgeBaseName: string;
  knowledgeReleaseId: string;
  knowledgeReleaseVersion: string;
  snapshotId: string;
}

export interface KnowledgeUploadSession {
  id: string;
  knowledgeBaseId: string;
  status: string;
  acceptedTypes: string[];
}

export interface KnowledgeFile {
  id: string;
  knowledgeBaseId: string;
  uploadSessionId: string;
  sourceType: KnowledgeImportSourceType;
  sourceUri: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
  status: KnowledgeFileStatus;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeImportJob {
  id: string;
  knowledgeBaseId: string;
  fileId: string;
  sourceType: KnowledgeImportSourceType;
  sourceUri: string;
  fileName: string;
  status: KnowledgeImportJobStatus;
  stage: KnowledgeImportJobStage;
  progressPercent: number;
  retryCount: number;
  retryable: boolean;
  failureReason: string | null;
  startedAt: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface KnowledgeDocument {
  id: string;
  knowledgeBaseId: string;
  fileId: string;
  title: string;
  sourceUri: string;
  documentType: string;
  status: string;
  chunkCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface KnowledgeDocumentDeletionBlocker {
  snapshotId: string;
  status: string;
  stage: string;
  retrievalMode: string;
  reason: string;
}

export interface KnowledgeDocumentDeletionPreview {
  documentId: string;
  knowledgeBaseId: string;
  fileId: string;
  fileName: string;
  sourceUri: string;
  title: string;
  chunkCount: number;
  canDelete: boolean;
  blockers: KnowledgeDocumentDeletionBlocker[];
}

export interface KnowledgeDocumentDeletionResult {
  documentId: string;
  knowledgeBaseId: string;
  fileId: string;
  fileName: string;
  title: string;
  deletedChunkCount: number;
  deletedImportJobCount: number;
  deletedDocumentCount: number;
  deletedStorageObject: boolean;
}

export interface KnowledgeSnapshot {
  id: string;
  knowledgeBaseId: string;
  retrievalBackend: 'PGVECTOR';
  retrievalMode: string;
  status: KnowledgeIndexSnapshotStatus;
  stage: KnowledgeIndexSnapshotStage;
  progressPercent: number;
  retryCount: number;
  retryable: boolean;
  documentCount: number;
  chunkCount: number;
  failureReason: string | null;
  startedAt: string | null;
  builtAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export type KnowledgeIndexSnapshot = KnowledgeSnapshot;

export interface KnowledgeUploadCompletion {
  file: KnowledgeFile;
  importJob: KnowledgeImportJob;
}

export interface KnowledgeRetrievalPreviewRequest {
  snapshotId: string;
  query: string;
  topK?: number;
  minScore?: number;
  retrievalMode?: 'LEXICAL' | 'VECTOR' | 'HYBRID' | null;
}

export interface KnowledgeRetrievalPreviewHit {
  chunkId: string;
  documentId: string;
  documentTitle: string;
  sourceUri: string;
  snippet: string;
  score: number;
  pageNumber: number | null;
  headingPath: string;
}

export interface KnowledgeRetrievalPreviewResult {
  hits: KnowledgeRetrievalPreviewHit[];
  lowConfidence: boolean;
}

export interface KnowledgeRelease {
  id: string;
  knowledgeBaseId: string;
  version: string;
  status: VersionStatus;
  summary: string;
  snapshotId: string;
  retrievalProfile: KnowledgeRetrievalProfile;
  createdAt: string;
  publishedAt: string | null;
}

export interface KnowledgeBase {
  id: string;
  name: string;
  shareScope: ShareScope;
  ownerType: ResourceOwnerType;
  ownerId: string;
  summary: string;
  steward: string;
  tags: string[];
  latestRelease: KnowledgeRelease | null;
  effectiveRelease: KnowledgeRelease | null;
  releases: KnowledgeRelease[];
}

export interface KnowledgeReference {
  knowledgeBaseId: string;
  referenceKind: string;
  sourceType: string;
  sourceId: string;
  sourceName: string;
  knowledgeReleaseId: string | null;
  knowledgeReleaseVersion: string | null;
  blocksDeletion: boolean;
}

export interface ObjectReferenceRelation {
  relationKind: string;
  relationRole: string;
  relationMode: ReferenceRelationMode;
  impactLevel: ReferenceImpactLevel;
  targetType: string;
  targetId: string;
  targetName: string;
  releaseId: string | null;
  releaseVersion: string | null;
  resourceVersionId: string | null;
  resourceVersion: string | null;
  knowledgeReleaseId: string | null;
  knowledgeReleaseVersion: string | null;
}

export interface ObjectReferenceAnalysis {
  objectType: ReferenceObjectType;
  objectId: string;
  objectName: string;
  relations: ObjectReferenceRelation[];
}

export interface DeletionCascadeItem {
  action: string;
  relationKind: string;
  targetType: string;
  targetId: string;
  targetName: string;
  description: string;
  releaseVersion: string | null;
  resourceVersion: string | null;
  knowledgeReleaseVersion: string | null;
}

export interface DeletionImpactPreview {
  objectType: ReferenceObjectType;
  objectId: string;
  objectName: string;
  canDelete: boolean;
  blockers: ObjectReferenceRelation[];
  advisories: ObjectReferenceRelation[];
  cascadeDeletes: DeletionCascadeItem[];
}

export interface PlatformEvent {
  id: string;
  eventType: string;
  aggregateType: PlatformAggregateType;
  aggregateId: string;
  actorId: string | null;
  payload: Record<string, unknown>;
  occurredAt: string;
}

export interface PlatformEventPage {
  items: PlatformEvent[];
  nextCursor: string | null;
}

export interface ToolOperation {
  name: string;
  description: string;
  inputSchema: string;
  outputSchema: string;
}

export interface ToolConnectorConfig {
  connectorType: string;
  accountId?: string | null;
  timeoutSeconds: number;
  retryPolicy: string;
  config: Record<string, unknown>;
  operationMappings: Record<string, Record<string, unknown>>;
}

export interface ToolConfig {
  operations: ToolOperation[];
  connector: ToolConnectorConfig;
}

export interface LlmModelConfig {
  providerType: 'OPENAI' | 'OPENAI_COMPATIBLE';
  modelId: string;
  baseUrl: string;
  apiKeyEnvVar: string;
  temperature: number;
  maxTokens: number;
  privateDeployment: boolean;
  enableThinking: boolean | null;
  reasoningEffort: string | null;
}

export interface SkillConfig {
  skillName: string;
  skillDesc: string;
  skillPrompt: string;
}

export interface ResourceVersionConfiguration {
  type: ResourceType;
  tool?: ToolConfig | null;
  llmModel?: LlmModelConfig | null;
  skill?: SkillConfig | null;
}

export interface ResourceVersion {
  id: string;
  resourceId: string;
  version: string;
  status: VersionStatus;
  summary: string;
  createdAt: string;
  publishedAt: string | null;
  configuration: ResourceVersionConfiguration;
}

export interface ResourceBlueprint {
  type: ResourceType;
  label: string;
  description: string;
  maintainedFields: string[];
  defaultConfiguration: ResourceVersionConfiguration;
}

export interface PlaybookExecutionPolicy {
  timeoutPolicy: string | null;
  retryPolicy: string | null;
}

export type PlaybookNodeType = 'STEP' | 'TOOL_TASK' | 'HUMAN_TASK' | 'EXTERNAL_INTERACTION' | 'END';

export interface PlaybookNodeLayout {
  x: number;
  y: number;
}

export interface PlaybookNode {
  nodeKey: string;
  nodeName: string;
  nodeType: PlaybookNodeType;
  description: string | null;
  scriptRef: string | null;
  scriptVersion: string | null;
  toolId: string | null;
  toolOperation: string | null;
  config: Record<string, unknown>;
  layout: PlaybookNodeLayout;
}

export interface PlaybookEdge {
  edgeKey: string;
  sourceNodeKey: string;
  targetNodeKey: string;
  routeKey: string | null;
  label: string | null;
  defaultEdge: boolean;
}

export interface Resource {
  id: string;
  name: string;
  type: ResourceType;
  shareScope: ShareScope;
  ownerType: ResourceOwnerType;
  ownerId: string;
  summary: string;
  steward: string;
  tags: string[];
  latestVersion: ResourceVersion | null;
  effectiveVersion: ResourceVersion | null;
  versions: ResourceVersion[];
}

export interface ResourceReference {
  resourceId: string;
  resourceName: string;
  type: ResourceType;
  shareScope: ShareScope;
  ownerLabel: string;
  latestVersion: string | null;
  effectiveVersion: string | null;
  referenceKind: string;
  sourceType: string;
  sourceId: string;
  sourceName: string;
  resourceVersionId: string | null;
  resourceVersion: string | null;
  blocksDeletion: boolean;
}

export interface ResourceCenter {
  totalResources: number;
  sharedResources: number;
  privateResources: number;
  references: ResourceReference[];
}

export interface CreateResourcePayload {
  name: string;
  type: ResourceType;
  shareScope: ShareScope;
  ownerType: ResourceOwnerType;
  ownerId: string;
  summary: string;
  steward: string;
  tags: string[];
  initialVersion: CreateResourceVersionPayload;
}

export interface CreateResourceVersionPayload {
  summary: string;
  status: VersionStatus;
  configuration: ResourceVersionConfiguration;
}

export interface UpdateResourcePayload {
  name: string;
  shareScope: ShareScope;
  ownerType: ResourceOwnerType;
  ownerId: string;
  summary: string;
  steward: string;
  tags: string[];
}

export interface UpdateResourceVersionPayload {
  summary: string;
  status: VersionStatus;
  configuration: ResourceVersionConfiguration;
}

export interface CreateKnowledgeBasePayload {
  name: string;
  shareScope: ShareScope;
  ownerType: ResourceOwnerType;
  ownerId: string;
  summary: string;
  steward: string;
  tags: string[];
}

export interface UpdateKnowledgeBasePayload {
  name: string;
  shareScope: ShareScope;
  ownerType: ResourceOwnerType;
  ownerId: string;
  summary: string;
  steward: string;
  tags: string[];
}

export interface CreateKnowledgeReleasePayload {
  summary: string;
  status: VersionStatus;
  snapshotId: string;
  retrievalProfile: KnowledgeRetrievalProfile;
}

export interface RoleConfiguration {
  role: string; responsibility: string; systemPrompt: string; modelVersionId: string | null;
  privacyModelVersionId: string | null; privacyMappingEnabled: boolean; knowledgeReleaseId: string | null;
  memoryWindowSize: number; skillVersionIds: string[]; toolVersionIds: string[]; playbookVersionIds: string[];
}
export interface RoleTemplateVersion { id: string; templateId: string; version: string; status: VersionStatus; createdAt: string; publishedAt: string | null; configuration: RoleConfiguration }
export interface OwnedConfiguration { name: string; description: string; ownerType: ResourceOwnerType; ownerId: string }
export interface RoleTemplate extends OwnedConfiguration { id: string; latestVersion: RoleTemplateVersion | null; effectiveVersion: RoleTemplateVersion | null; versions: RoleTemplateVersion[] }
export interface CreateRoleTemplateVersionPayload { status: VersionStatus; configuration: RoleConfiguration }
export interface CreateRoleTemplatePayload extends OwnedConfiguration { initialVersion?: CreateRoleTemplateVersionPayload }
export interface Entry extends OwnedConfiguration { id: string; shareScope: ShareScope; receptionTemplateVersionId: string; enabled: boolean; allowedTemplateIds: string[]; capabilityGrants: import('./agent').GrantRequest[] }
export type EntryPayload = Omit<Entry, 'id'>;
export interface PlaybookDefinition { description: string; inputSchema: string; resultSchema: string; executionPolicy: PlaybookExecutionPolicy; allowHumanTask: boolean; allowExternalInteraction: boolean; entryNodeKey: string; nodes: PlaybookNode[]; edges: PlaybookEdge[] }
export interface PlaybookVersion { id: string; playbookId: string; version: string; status: VersionStatus; createdAt: string; publishedAt: string | null; definition: PlaybookDefinition }
export interface Playbook extends OwnedConfiguration { id: string; latestVersion: PlaybookVersion | null; effectiveVersion: PlaybookVersion | null; versions: PlaybookVersion[] }
export interface CreatePlaybookVersionPayload { status: VersionStatus; definition: PlaybookDefinition }
export interface CreatePlaybookPayload extends OwnedConfiguration { initialVersion?: CreatePlaybookVersionPayload }
export type UpdatePlaybookPayload = OwnedConfiguration;
export interface CatalogSummary { roleTemplates: RoleTemplate[]; entries: Entry[]; playbooks: Playbook[]; resources: Resource[]; knowledgeBases: KnowledgeBase[]; resourceCenter: ResourceCenter; resourceBlueprints: ResourceBlueprint[] }
