import { NextRequest, NextResponse } from 'next/server';

import { getCacheTime } from '@/lib/config';
import { fetchDoubanData } from '@/lib/douban';
import { DoubanResult } from '@/lib/types';

export const runtime = 'edge';

interface CollectionResponse {
  subject_collection_items?: Array<{
    id: string;
    title: string;
    card_subtitle?: string;
    cover_url?: string;
    pic?: { large?: string; normal?: string };
    rating?: { value?: number } | null;
  }>;
}

const COLLECTIONS = new Set(['movie_top250', 'movie_weekly_best']);

export async function GET(request: NextRequest) {
  const params = new URL(request.url).searchParams;
  const id = params.get('id') || '';
  const start = Math.max(0, Number(params.get('start')) || 0);
  const limit = Math.min(100, Math.max(1, Number(params.get('limit')) || 25));

  if (!COLLECTIONS.has(id)) {
    return NextResponse.json({ error: '无效的榜单类型' }, { status: 400 });
  }

  try {
    const data = await fetchDoubanData<CollectionResponse>(
      `https://m.douban.com/rexxar/api/v2/subject_collection/${id}/items?start=${start}&count=${limit}`
    );
    const list = (data.subject_collection_items || []).map((item) => ({
      id: item.id,
      title: item.title,
      poster: item.cover_url || item.pic?.large || item.pic?.normal || '',
      rate: item.rating?.value ? item.rating.value.toFixed(1) : '',
      year: item.card_subtitle?.match(/(\d{4})/)?.[1] || '',
    }));
    const result: DoubanResult = { code: 200, message: '获取成功', list };
    const cacheTime = await getCacheTime();
    return NextResponse.json(result, {
      headers: {
        'Cache-Control': `public, max-age=${cacheTime}, s-maxage=${cacheTime}`,
      },
    });
  } catch (error) {
    return NextResponse.json(
      { error: '获取豆瓣榜单失败', details: (error as Error).message },
      { status: 502 }
    );
  }
}
