import type { PlayRecord } from './db.client';

/** Replacement characters indicate that a record was decoded with the wrong charset. */
export function isDisplayablePlayRecord(record: PlayRecord): boolean {
  return ![record.title, record.source_name, record.search_title].some(
    (value) => typeof value === 'string' && value.includes('\uFFFD')
  );
}
