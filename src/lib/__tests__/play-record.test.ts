import type { PlayRecord } from '../db.client';
import { isDisplayablePlayRecord } from '../play-record';

const record = {
  title: '小城良方',
  source_name: '量子资源',
  search_title: '小城良方',
} as PlayRecord;

describe('play record display guard', () => {
  it('keeps valid Chinese metadata', () => {
    expect(isDisplayablePlayRecord(record)).toBe(true);
  });

  it('hides records already damaged by an invalid charset conversion', () => {
    expect(isDisplayablePlayRecord({ ...record, title: '������' })).toBe(false);
  });
});
