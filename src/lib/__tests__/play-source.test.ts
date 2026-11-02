import {
  isPlayableSearchResult,
  mergePlaybackSource,
  playbackSourceChanged,
  shouldSwitchPlaybackUrl,
} from '../play-source';
import type { SearchResult } from '../types';

const partial: SearchResult = {
  id: '72108',
  title: '仙逆',
  poster: '',
  episodes: [],
  episodes_titles: [],
  source: '量子',
  source_name: '量子资源',
  year: '2023',
};

const complete: SearchResult = {
  ...partial,
  poster: 'https://example.com/poster.jpg',
  episodes: ['https://example.com/1.m3u8'],
  episodes_titles: ['第1集'],
  desc: '完整简介',
};

describe('play source recovery', () => {
  it('does not treat an empty detail response as playable', () => {
    expect(isPlayableSearchResult(partial)).toBe(false);
    expect(isPlayableSearchResult(complete)).toBe(true);
  });

  it('requires the requested history episode to exist', () => {
    expect(isPlayableSearchResult(complete, 0)).toBe(true);
    expect(isPlayableSearchResult(complete, 3)).toBe(false);
  });

  it('lets a complete search result repair the same partial source', () => {
    const merged = mergePlaybackSource(partial, complete);
    expect(merged.poster).toBe(complete.poster);
    expect(merged.episodes).toEqual(complete.episodes);
    expect(merged.episodes_titles).toEqual(complete.episodes_titles);
    expect(playbackSourceChanged(partial, merged)).toBe(true);
  });

  it('does not erase useful metadata with an empty update', () => {
    const merged = mergePlaybackSource(complete, partial);
    expect(merged.poster).toBe(complete.poster);
    expect(merged.episodes).toEqual(complete.episodes);
    expect(merged.desc).toBe(complete.desc);
  });

  it('keeps the loaded episode URL while appending newly discovered episodes', () => {
    const current = {
      ...complete,
      episodes: ['https://detail.example/1.m3u8'],
    };
    const incoming = {
      ...complete,
      episodes: [
        'https://search.example/1.m3u8',
        'https://search.example/2.m3u8',
      ],
    };
    expect(mergePlaybackSource(current, incoming).episodes).toEqual([
      'https://detail.example/1.m3u8',
      'https://search.example/2.m3u8',
    ]);
  });

  it('does not switch the player when only metadata changed', () => {
    const url = 'https://example.com/1.m3u8';
    expect(shouldSwitchPlaybackUrl(url, url)).toBe(false);
    expect(shouldSwitchPlaybackUrl(url, 'https://example.com/2.m3u8')).toBe(
      true
    );
  });
});
