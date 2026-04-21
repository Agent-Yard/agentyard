import type { InjectionKey, ShallowRef, VNodeChild } from 'vue';

export interface PageHeadActionsRegistration {
  owner: symbol;
  render: () => VNodeChild;
}

export const pageHeadActionsKey: InjectionKey<ShallowRef<PageHeadActionsRegistration | null>> = Symbol('page-head-actions');
