import {
  matchesSearchYear,
  normalizeSearchYear,
  resolvePlaybackTitle,
  resolveSearchYear,
} from '../search-title';

describe('cross-source year matching', () => {
  it('does not turn unknown metadata into a restrictive filter', () => {
    expect(normalizeSearchYear('unknown')).toBe('');
    expect(matchesSearchYear('2026', 'unknown')).toBe(true);
  });

  it('keeps sources with a missing year when the title matches', () => {
    expect(matchesSearchYear('', '2026')).toBe(true);
    expect(matchesSearchYear('unknown', '2026')).toBe(true);
  });

  it('rejects only a different known release year', () => {
    expect(matchesSearchYear('2026', '2026')).toBe(true);
    expect(matchesSearchYear('2025', '2026')).toBe(false);
  });

  it('preserves the canonical year across a source with missing metadata', () => {
    expect(resolveSearchYear('unknown', '2026')).toBe('2026');
    expect(resolveSearchYear('2025', '2026')).toBe('2025');
  });
});

describe('playback title recovery', () => {
  it('keeps the URL title when a detail response has an empty title', () => {
    expect(resolvePlaybackTitle('', ' 择日飞升 ', 'fallback')).toBe('择日飞升');
  });

  it('prefers a usable title returned by the selected source', () => {
    expect(resolvePlaybackTitle('仙逆', '旧标题')).toBe('仙逆');
  });
});
