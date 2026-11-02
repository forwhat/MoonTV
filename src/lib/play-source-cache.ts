import { getAuthInfoFromBrowserCookie } from './auth';
import { mergePlaybackSource } from './play-source';
import type { SearchResult } from './types';

const STORAGE_KEY = 'moontv:play-source-cache:v1';
const STORE_VERSION = 1;
const MAX_SEARCHES = 6;

/** Keep temporarily unavailable sources long enough to survive flaky networks. */
export const PLAYBACK_SOURCE_CACHE_TTL_MS = 2 * 60 * 60 * 1000;

interface StoredSource {
  result: SearchResult;
  lastSeenAt: number;
}

interface StoredSearch {
  accessedAt: number;
  sourceOrder: string[];
  sources: StoredSource[];
}

interface PlaybackSourceStore {
  version: number;
  searches: Record<string, StoredSearch>;
}

export interface PlaybackSourceCacheSnapshot {
  results: SearchResult[];
  sourceOrder: string[];
}

function emptyStore(): PlaybackSourceStore {
  return { version: STORE_VERSION, searches: {} };
}

function readStore(): PlaybackSourceStore {
  if (typeof window === 'undefined') return emptyStore();
  try {
    const parsed = JSON.parse(
      window.localStorage.getItem(STORAGE_KEY) || 'null'
    ) as PlaybackSourceStore | null;
    if (
      parsed?.version === STORE_VERSION &&
      parsed.searches &&
      typeof parsed.searches === 'object'
    ) {
      return parsed;
    }
  } catch {
    // Corrupt or unavailable storage must never block playback.
  }
  return emptyStore();
}

function isSearchResult(value: unknown): value is SearchResult {
  if (!value || typeof value !== 'object') return false;
  const result = value as Partial<SearchResult>;
  return Boolean(
    typeof result.id === 'string' &&
      result.id &&
      typeof result.source === 'string' &&
      result.source &&
      typeof result.title === 'string' &&
      Array.isArray(result.episodes) &&
      Array.isArray(result.episodes_titles)
  );
}

function writeStore(store: PlaybackSourceStore, preferredKey: string): void {
  if (typeof window === 'undefined') return;

  const searches = Object.entries(store.searches)
    .sort((a, b) => b[1].accessedAt - a[1].accessedAt)
    .slice(0, MAX_SEARCHES);
  const bounded = {
    version: STORE_VERSION,
    searches: Object.fromEntries(searches),
  };

  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(bounded));
  } catch {
    // Episode URLs can be large. If quota is tight, retain only this video.
    const current = bounded.searches[preferredKey];
    if (!current) return;
    try {
      window.localStorage.setItem(
        STORAGE_KEY,
        JSON.stringify({
          version: STORE_VERSION,
          searches: { [preferredKey]: current },
        })
      );
    } catch {
      // Storage is optional; the live stream still works without it.
    }
  }
}

export function playbackSourceIdentity(
  result: Pick<SearchResult, 'source' | 'id'>
): string {
  return JSON.stringify([result.source, result.id]);
}

export function getPlaybackSourceCacheScope(): string {
  if (typeof window === 'undefined') return 'server';
  const runtime = (
    window as Window & { RUNTIME_CONFIG?: Record<string, unknown> }
  ).RUNTIME_CONFIG;
  return JSON.stringify([
    window.location.origin,
    String(runtime?.ANDROID_MODE || 'web'),
    String(runtime?.STORAGE_TYPE || 'unknown'),
    getAuthInfoFromBrowserCookie()?.username || 'anonymous',
  ]);
}

export function buildPlaybackSourceCacheKey(input: {
  scope: string;
  query: string;
  year?: string;
  type?: string;
}): string {
  return JSON.stringify([
    input.scope,
    input.query.trim().toLocaleLowerCase(),
    input.year?.trim() || '',
    input.type || '',
  ]);
}

/** Merge fresh data without changing the position of already visible sources. */
export function mergePlaybackSourceLists(
  current: SearchResult[],
  incoming: SearchResult[]
): SearchResult[] {
  const merged = [...current];
  for (const result of incoming) {
    const identity = playbackSourceIdentity(result);
    const index = merged.findIndex(
      (item) => playbackSourceIdentity(item) === identity
    );
    if (index < 0) {
      merged.push(result);
    } else {
      const enriched = mergePlaybackSource(merged[index], result);
      // This helper merges a newly successful response into a cached snapshot,
      // so fresh episode URLs must win. The player-detail merge uses
      // mergePlaybackSource directly and keeps its already loaded URL.
      merged[index] = {
        ...enriched,
        episodes: result.episodes.some((url) => url?.trim())
          ? result.episodes
          : enriched.episodes,
        episodes_titles: result.episodes_titles.some((title) => title?.trim())
          ? result.episodes_titles
          : enriched.episodes_titles,
      };
    }
  }
  return merged;
}

/** Sort by configured source order while keeping matches within one source stable. */
export function sortPlaybackSources(
  sources: SearchResult[],
  sourceOrder: string[]
): SearchResult[] {
  if (sourceOrder.length === 0) return [...sources];
  const order = new Map(sourceOrder.map((key, index) => [key, index]));
  return sources
    .map((source, index) => ({ source, index }))
    .sort((a, b) => {
      const aOrder = order.get(a.source.source) ?? Number.MAX_SAFE_INTEGER;
      const bOrder = order.get(b.source.source) ?? Number.MAX_SAFE_INTEGER;
      return aOrder - bOrder || a.index - b.index;
    })
    .map(({ source }) => source);
}

export function loadPlaybackSourceCache(
  key: string,
  now = Date.now()
): PlaybackSourceCacheSnapshot {
  const entry = readStore().searches[key];
  if (!entry || !Array.isArray(entry.sources)) {
    return { results: [], sourceOrder: [] };
  }

  const sources = entry.sources.filter(
    (item) =>
      isSearchResult(item?.result) &&
      Number.isFinite(item.lastSeenAt) &&
      now - item.lastSeenAt <= PLAYBACK_SOURCE_CACHE_TTL_MS
  );
  return {
    results: sortPlaybackSources(
      sources.map((item) => item.result),
      Array.isArray(entry.sourceOrder) ? entry.sourceOrder : []
    ),
    sourceOrder: Array.isArray(entry.sourceOrder) ? entry.sourceOrder : [],
  };
}

/**
 * Persist the merged list. Only identities returned successfully in this run
 * advance lastSeenAt; cached fallbacks keep their original expiry.
 */
export function savePlaybackSourceCache(
  key: string,
  results: SearchResult[],
  freshIdentities: ReadonlySet<string>,
  sourceOrder: string[],
  now = Date.now()
): void {
  const store = readStore();
  const previous = new Map(
    (store.searches[key]?.sources || []).map((item) => [
      playbackSourceIdentity(item.result),
      item,
    ])
  );

  const sources = sortPlaybackSources(results, sourceOrder)
    .map((result): StoredSource => {
      const identity = playbackSourceIdentity(result);
      const old = previous.get(identity);
      return {
        result,
        lastSeenAt: freshIdentities.has(identity)
          ? now
          : old?.lastSeenAt ?? now,
      };
    })
    .filter((item) => now - item.lastSeenAt <= PLAYBACK_SOURCE_CACHE_TTL_MS);

  if (sources.length === 0) return;
  store.searches[key] = {
    accessedAt: now,
    sourceOrder: [...sourceOrder],
    sources,
  };
  writeStore(store, key);
}
