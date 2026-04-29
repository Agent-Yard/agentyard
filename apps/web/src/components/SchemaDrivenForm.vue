<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  displayComponent,
  evaluateVisibilityCondition,
  fieldOptions,
  getJsonPointerValue,
  getSecretBoundaryViolations,
  isRequiredBySchema,
  isSecretField,
  isRenderableField,
  mapApiValidationErrors,
  sanitizeInitialModelValue,
  setJsonPointerValue,
  validateSchemaDrivenForm,
} from './schemaDrivenForm';
import type {
  ApiValidationErrors,
  JsonObject,
  SchemaDrivenFormMode,
  SchemaDrivenFormUiField,
  SchemaDrivenFormValidationResult,
} from './schemaDrivenForm';

const props = withDefaults(defineProps<{
  schema: JsonObject;
  uiSchema: SchemaDrivenFormUiField[];
  modelValue: JsonObject;
  mode?: SchemaDrivenFormMode;
  apiFieldErrors?: ApiValidationErrors;
}>(), {
  mode: 'config',
  apiFieldErrors: null,
});

const emit = defineEmits<{
  'update:modelValue': [value: JsonObject];
  validation: [result: SchemaDrivenFormValidationResult];
}>();

const secretDraftValues = ref<Record<string, unknown>>({});

function isCredentialSecretField(field: SchemaDrivenFormUiField): boolean {
  return props.mode === 'credential' && isSecretField(field);
}

const effectiveModelValue = computed(() => {
  let current = sanitizeInitialModelValue(
    props.modelValue ?? {},
    props.uiSchema ?? [],
    props.mode,
  );

  if (props.mode === 'credential') {
    for (const [pointer, value] of Object.entries(secretDraftValues.value)) {
      current = setJsonPointerValue(current, pointer, value);
    }
  }

  return current;
});

const boundaryViolations = computed(() => getSecretBoundaryViolations(props.uiSchema ?? [], props.mode));
const apiErrors = computed(() => mapApiValidationErrors(props.apiFieldErrors));
const validationResult = computed(() => validateSchemaDrivenForm(props.schema, effectiveModelValue.value, {
  mode: props.mode,
  uiSchema: props.uiSchema,
}));

const visibleFields = computed(() => (props.uiSchema ?? [])
  .filter((field) => isRenderableField(field, props.mode))
  .filter((field) => evaluateVisibilityCondition(field.visibilityCondition, effectiveModelValue.value))
  .slice()
  .sort((left: SchemaDrivenFormUiField, right: SchemaDrivenFormUiField) => (
    (left.order ?? 0) - (right.order ?? 0) || left.label.localeCompare(right.label)
  )));

function validate() {
  const result = validateSchemaDrivenForm(props.schema, effectiveModelValue.value, {
    mode: props.mode,
    uiSchema: props.uiSchema,
  });
  emit('validation', result);
  return result;
}

defineExpose({ validate });

function fieldValue(field: SchemaDrivenFormUiField): unknown {
  if (isCredentialSecretField(field)) {
    return secretDraftValues.value[field.key];
  }
  return getJsonPointerValue(effectiveModelValue.value, field.key);
}

function updateField(field: SchemaDrivenFormUiField, value: unknown) {
  if (isCredentialSecretField(field)) {
    if (value === undefined || value === '') {
      delete secretDraftValues.value[field.key];
    } else {
      secretDraftValues.value[field.key] = value;
    }
  }

  emit('update:modelValue', setJsonPointerValue(effectiveModelValue.value, field.key, value));
}

function fieldMessages(field: SchemaDrivenFormUiField): string[] {
  return [
    ...(apiErrors.value[field.key] ?? []),
    ...(validationResult.value.fieldErrors[field.key] ?? []),
  ];
}

function validateStatus(field: SchemaDrivenFormUiField) {
  return fieldMessages(field).length > 0 ? 'error' : undefined;
}

function jsonEditorValue(field: SchemaDrivenFormUiField): string {
  const value = fieldValue(field);
  if (value === undefined) {
    return '';
  }
  if (typeof value === 'string') {
    return value;
  }
  return JSON.stringify(value, null, 2);
}

function updateJsonEditor(field: SchemaDrivenFormUiField, rawValue: string) {
  if (!rawValue.trim()) {
    updateField(field, undefined);
    return;
  }

  try {
    updateField(field, JSON.parse(rawValue));
  } catch {
    updateField(field, rawValue);
  }
}
</script>

<template>
  <div class="schema-driven-form">
    <a-alert
      v-if="boundaryViolations.length > 0"
      type="error"
      show-icon
      style="margin-bottom: 16px"
      message="配置 Schema 包含不可渲染的敏感字段"
      :description="boundaryViolations.map((violation) => `${violation.pointer}: ${violation.reason}`).join('；')"
    />

    <a-form layout="vertical">
      <a-form-item
        v-for="field in visibleFields"
        :key="field.key"
        :label="field.label"
        :required="isRequiredBySchema(schema, field.key)"
        :extra="field.description"
        :validate-status="validateStatus(field)"
        :help="fieldMessages(field).join('；') || undefined"
      >
        <a-textarea
          v-if="['textarea'].includes(displayComponent(field, mode))"
          :value="fieldValue(field) as string | undefined"
          :placeholder="field.placeholder"
          :disabled="field.readOnly"
          :rows="4"
          @update:value="(value: string) => updateField(field, value)"
        />

        <a-input-password
          v-else-if="displayComponent(field, mode) === 'password'"
          :value="fieldValue(field) as string | undefined"
          :placeholder="field.placeholder"
          :disabled="field.readOnly"
          autocomplete="new-password"
          @update:value="(value: string) => updateField(field, value)"
        />

        <a-input-number
          v-else-if="displayComponent(field, mode) === 'number'"
          :value="fieldValue(field) as number | undefined"
          :placeholder="field.placeholder"
          :disabled="field.readOnly"
          style="width: 100%"
          @update:value="(value: number | null) => updateField(field, value ?? undefined)"
        />

        <a-switch
          v-else-if="displayComponent(field, mode) === 'boolean'"
          :checked="Boolean(fieldValue(field))"
          :disabled="field.readOnly"
          @update:checked="(value: boolean) => updateField(field, value)"
        />

        <a-select
          v-else-if="displayComponent(field, mode) === 'select'"
          :value="fieldValue(field) as string | number | boolean | undefined"
          :options="fieldOptions(field, schema)"
          :placeholder="field.placeholder"
          :disabled="field.readOnly"
          allow-clear
          @update:value="(value: string | number | boolean | undefined) => updateField(field, value)"
        />

        <a-select
          v-else-if="displayComponent(field, mode) === 'multiSelect'"
          :value="fieldValue(field) as Array<string | number> | undefined"
          :options="fieldOptions(field, schema)"
          :placeholder="field.placeholder"
          :disabled="field.readOnly"
          mode="multiple"
          @update:value="(value: Array<string | number>) => updateField(field, value)"
        />

        <a-radio-group
          v-else-if="displayComponent(field, mode) === 'radio'"
          :value="fieldValue(field)"
          :options="fieldOptions(field, schema)"
          :disabled="field.readOnly"
          @update:value="(value: string | number | boolean) => updateField(field, value)"
        />

        <a-checkbox-group
          v-else-if="displayComponent(field, mode) === 'checkboxGroup'"
          :value="fieldValue(field) as Array<string | number | boolean> | undefined"
          :options="fieldOptions(field, schema)"
          :disabled="field.readOnly"
          @update:value="(value: Array<string | number | boolean>) => updateField(field, value)"
        />

        <a-textarea
          v-else-if="displayComponent(field, mode) === 'json'"
          :value="jsonEditorValue(field)"
          :placeholder="field.placeholder"
          :disabled="field.readOnly"
          :rows="6"
          @update:value="(value: string) => updateJsonEditor(field, value)"
        />

        <a-input
          v-else-if="['text', 'cron', 'duration', 'url', 'email', 'dateTime'].includes(displayComponent(field, mode))"
          :value="fieldValue(field) as string | undefined"
          :placeholder="field.placeholder"
          :disabled="field.readOnly"
          :type="displayComponent(field, mode) === 'email' ? 'email' : displayComponent(field, mode) === 'url' ? 'url' : 'text'"
          @update:value="(value: string) => updateField(field, value)"
        />

        <a-textarea
          v-else
          :value="jsonEditorValue(field)"
          :placeholder="field.placeholder"
          :disabled="field.readOnly"
          :rows="6"
          @update:value="(value: string) => updateJsonEditor(field, value)"
        />
      </a-form-item>
    </a-form>
  </div>
</template>
