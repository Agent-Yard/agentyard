import type { PlatformAggregateType, PlatformEventPage } from '../types';

export interface ObjectHistoryState {
  events: PlatformEventPage['items'];
  nextCursor: string | null;
  loading: boolean;
  loadingMore: boolean;
  errorMessage: string;
}

export interface ObjectHistoryParams {
  aggregateType: PlatformAggregateType;
  objectId: string | null | undefined;
}

type FetchPlatformEvents = (params: {
  aggregateType: PlatformAggregateType;
  aggregateId: string;
  limit: number;
  cursor?: string;
}) => Promise<PlatformEventPage>;

export function createObjectHistoryState(): ObjectHistoryState {
  return {
    events: [],
    nextCursor: null,
    loading: false,
    loadingMore: false,
    errorMessage: '',
  };
}

export function createObjectHistoryLoader(
  state: ObjectHistoryState,
  fetchPlatformEvents: FetchPlatformEvents,
) {
  let requestId = 0;

  async function load(params: ObjectHistoryParams, cursor?: string) {
    const currentRequestId = ++requestId;

    if (!params.objectId) {
      state.events = [];
      state.nextCursor = null;
      state.errorMessage = '';
      state.loading = false;
      state.loadingMore = false;
      return;
    }

    if (cursor) {
      state.loadingMore = true;
    } else {
      state.events = [];
      state.nextCursor = null;
      state.loading = true;
      state.loadingMore = false;
      state.errorMessage = '';
    }

    try {
      const page = await fetchPlatformEvents({
        aggregateType: params.aggregateType,
        aggregateId: params.objectId,
        limit: 20,
        cursor,
      });
      if (currentRequestId !== requestId) {
        return;
      }
      state.events = cursor ? [...state.events, ...page.items] : page.items;
      state.nextCursor = page.nextCursor;
    } catch (error) {
      if (currentRequestId !== requestId) {
        return;
      }
      state.errorMessage = error instanceof Error ? error.message : '加载操作历史失败';
    } finally {
      if (currentRequestId !== requestId) {
        return;
      }
      state.loading = false;
      state.loadingMore = false;
    }
  }

  return { load };
}
