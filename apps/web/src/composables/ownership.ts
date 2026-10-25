import type { ResourceOwnerType } from '../types';
export interface OwnershipOption { ownerType: ResourceOwnerType; ownerId: string; label: string }
export const ownershipTypes = [
  { label: '个人', value: 'PERSON' }, { label: '组织', value: 'ORGANIZATION' }, { label: '平台', value: 'PLATFORM' },
];
export function ownerChoices(options: OwnershipOption[], type: ResourceOwnerType) {
  return options.filter(option => option.ownerType === type).map(option => ({ label: option.label, value: option.ownerId }));
}
