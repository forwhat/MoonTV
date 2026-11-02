function normalizeTitle(title: string): string {
  return title
    .replace(/\uff08/g, '(')
    .replace(/\uff09/g, ')')
    .trim()
    .replace(/\s+/g, ' ')
    .replace(/\s*([()])\s*/g, '$1')
    .toLowerCase();
}

/** Full-title equality for playback sources; never discard annotations or suffixes. */
export function matchesFullSearchTitle(title: string, query: string): boolean {
  const normalizedQuery = normalizeTitle(query);
  return (
    normalizedQuery.length > 0 && normalizeTitle(title) === normalizedQuery
  );
}

const RELEASE_YEAR = /(?:19|20)\d{2}/;

/** Only a real four-digit release year is safe to use as a cross-source filter. */
export function normalizeSearchYear(value: unknown): string {
  const match = String(value ?? '').match(RELEASE_YEAR);
  return match?.[0] ?? '';
}

/**
 * Sources frequently omit the year or return `unknown`. Missing metadata must
 * not hide an otherwise matching source; two known, different years still do.
 */
export function matchesSearchYear(
  candidate: unknown,
  target: unknown
): boolean {
  const normalizedTarget = normalizeSearchYear(target);
  if (!normalizedTarget) return true;

  const normalizedCandidate = normalizeSearchYear(candidate);
  return !normalizedCandidate || normalizedCandidate === normalizedTarget;
}

/** Keep the known canonical year when the selected source has no usable year. */
export function resolveSearchYear(
  candidate: unknown,
  fallback: unknown
): string {
  return normalizeSearchYear(candidate) || normalizeSearchYear(fallback);
}

/** Keep the first usable title when a detail endpoint omits `vod_name`. */
export function resolvePlaybackTitle(...values: unknown[]): string {
  for (const value of values) {
    const title = typeof value === 'string' ? value.trim() : '';
    if (title) return title;
  }
  return '';
}

// Common sentence punctuation and separators interpreted specially by upstream engines.
const SEARCH_TITLE_SEPARATORS =
  /[().,!?;:#&|/~\\\u3002\uff0c\u3001\uff01\uff1f\uff1b\uff1a\uff03\uff06\uff5c\uff0f\uff3c\uff5e\u301c\u2026]/;

// A pure year annotation must not outrank a short main title, e.g. 三体(2024).
const YEAR_FRAGMENT = /^(?:19|20)\d{2}$/;

/** Use the longest nonempty fragment; years never compete as the main title. */
function getMainTitle(title: string): string {
  const normalized = title
    .replace(/\uff08/g, '(')
    .replace(/\uff09/g, ')')
    .trim()
    .replace(/\s+/g, ' ');
  let longest = '';
  for (const fragment of normalized.split(SEARCH_TITLE_SEPARATORS)) {
    const candidate = fragment.trim();
    if (!candidate || YEAR_FRAGMENT.test(candidate)) continue;
    if (Array.from(candidate).length > Array.from(longest).length) {
      longest = candidate;
    }
  }
  // 整串只有年份时保持原样，避免返回空字符串
  return longest || normalized;
}

export function getFallbackSearchQuery(query: string): string | null {
  const main = getMainTitle(query);
  const original = query
    .replace(/\uff08/g, '(')
    .replace(/\uff09/g, ')')
    .trim()
    .replace(/\s+/g, ' ');
  return main && main !== original ? main : null;
}

/**
 * Relevance depends on main titles, but a query may also target a subtitle.
 * e.g. 「黑铁的鱼影」 should match 「名侦探柯南：黑铁的鱼影」.
 * Pure-year queries still never match year annotations.
 */
export function matchesSearchTitle(
  title: string,
  query: string,
  exact = false
): boolean {
  const mainQuery = normalizeTitle(getMainTitle(query) || query);
  if (!mainQuery) return false;

  const mainTitle = normalizeTitle(getMainTitle(title) || title);
  const fullTitle = normalizeTitle(title);

  if (exact) {
    return mainTitle === mainQuery || fullTitle === mainQuery;
  }

  // 纯年份查询只按主标题判断，避免把「三体(2024)」里的年份当成片名匹配。
  const isYearOnlyQuery = YEAR_FRAGMENT.test(normalizeTitle(query));
  return (
    mainTitle.includes(mainQuery) ||
    (!isYearOnlyQuery && fullTitle.includes(mainQuery))
  );
}
