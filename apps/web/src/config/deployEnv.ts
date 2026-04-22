export type DeployEnv = 'local' | 'dev' | 'test' | 'prd';

const SUPPORTED_DEPLOY_ENVS: readonly DeployEnv[] = ['local', 'dev', 'test', 'prd'];

function isDeployEnv(value: string): value is DeployEnv {
  return SUPPORTED_DEPLOY_ENVS.includes(value as DeployEnv);
}

export function resolveDeployEnv(rawValue?: string, viteMode?: string): DeployEnv {
  const normalizedValue = rawValue?.trim().toLowerCase();
  if (normalizedValue && isDeployEnv(normalizedValue)) {
    return normalizedValue;
  }

  const normalizedMode = viteMode?.trim().toLowerCase();
  if (normalizedMode === 'development') {
    return 'local';
  }

  return 'prd';
}

export function isNonPrdDeployEnv(deployEnv: DeployEnv): boolean {
  return deployEnv !== 'prd';
}
