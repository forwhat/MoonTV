import {
  buildPlaybackSourceCacheKey,
  loadPlaybackSourceCache,
  mergePlaybackSourceLists,
  PLAYBACK_SOURCE_CACHE_TTL_MS,
  playbackSourceIdentity,
  savePlaybackSourceCache,
  sortPlaybackSources,
} from '../play-source-cache';
import type { SearchResult } from '../types';

function source(key: string, id = key): SearchResult {
  return {
    id,
    title: '仙逆',
    poster: '',
    episodes: [`https://${key}.example/1.m3u8`],
    episodes_titles: ['第1集'],
    source: key,
    source_name: `${key}资源`,
    year: '2023',
  };
}

describe('playback source cache', () => {
  beforeEach(() => window.localStorage.clear());

  it('keeps existing positions while merging refreshed sources', () => {
    const merged = mergePlaybackSourceLists(
      [source('b'), source('a')],
      [source('a'), source('c')]
    );
    expect(merged.map((item) => item.source)).toEqual(['b', 'a', 'c']);
  });

  it('replaces cached episode URLs with a newly successful response', () => {
    const cached = source('a');
    const fresh = {
      ...cached,
      episodes: ['https://a.example/fresh.m3u8'],
    };
    expect(mergePlaybackSourceLists([cached], [fresh])[0].episodes).toEqual(
      fresh.episodes
    );
  });

  it('sorts sources by configured order instead of response timing', () => {
    expect(
      sortPlaybackSources(
        [source('slow'), source('fast'), source('unknown')],
        ['fast', 'slow']
      ).map((item) => item.source)
    ).toEqual(['fast', 'slow', 'unknown']);
  });

  it('keeps a timed-out source until its own last-success TTL expires', () => {
    const key = buildPlaybackSourceCacheKey({
      scope: 'server',
      query: '仙逆',
      year: '2023',
      type: 'tv',
    });
    const fast = source('fast');
    const slow = source('slow');
    const initial = new Set([
      playbackSourceIdentity(fast),
      playbackSourceIdentity(slow),
    ]);
    savePlaybackSourceCache(key, [fast, slow], initial, ['fast', 'slow'], 1000);

    savePlaybackSourceCache(
      key,
      [fast, slow],
      new Set([playbackSourceIdentity(fast)]),
      ['fast', 'slow'],
      2000
    );
    expect(loadPlaybackSourceCache(key, 2000).results).toHaveLength(2);
    expect(
      loadPlaybackSourceCache(key, 1000 + PLAYBACK_SOURCE_CACHE_TTL_MS + 1)
        .results
    ).toEqual([fast]);
  });
});
