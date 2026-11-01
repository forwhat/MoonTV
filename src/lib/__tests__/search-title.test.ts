import {
  matchesSearchYear,
  normalizeSearchYear,
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
