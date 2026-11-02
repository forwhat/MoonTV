import { resolvePlaybackTitle, resolveSearchYear } from './search-title';
import type { SearchResult } from './types';

export function isPlayableSearchResult(result: SearchResult | null): boolean {
  return Boolean(
    result?.id &&
      result.source &&
      Array.isArray(result.episodes) &&
      result.episodes.some(
        (episode) => typeof episode === 'string' && episode.trim().length > 0
      )
  );
}

function preferText(candidate: unknown, fallback: unknown): string {
  const value = typeof candidate === 'string' ? candidate.trim() : '';
  if (value) return value;
  return typeof fallback === 'string' ? fallback : '';
}

function preferList(candidate: unknown, fallback: string[]): string[] {
  return Array.isArray(candidate) &&
    candidate.some(
      (value) => typeof value === 'string' && value.trim().length > 0
    )
    ? candidate
    : fallback;
}

/** Let a complete search result repair a partial direct-detail response. */
export function mergePlaybackSource(
  current: SearchResult,
  incoming: SearchResult
): SearchResult {
  const resolvedYear = resolveSearchYear(incoming.year, current.year);
  return {
    ...current,
    ...incoming,
    title: resolvePlaybackTitle(incoming.title, current.title),
    poster: preferText(incoming.poster, current.poster),
    episodes: preferList(incoming.episodes, current.episodes),
    episodes_titles: preferList(
      incoming.episodes_titles,
      current.episodes_titles
    ),
    source_name: preferText(incoming.source_name, current.source_name),
    year: resolvedYear || preferText(incoming.year, current.year),
    class: preferText(incoming.class, current.class),
    desc: preferText(incoming.desc, current.desc),
    type_name: preferText(incoming.type_name, current.type_name),
    douban_id: incoming.douban_id || current.douban_id,
  };
}

export function playbackSourceChanged(
  current: SearchResult,
  incoming: SearchResult
): boolean {
  return JSON.stringify(current) !== JSON.stringify(incoming);
}
