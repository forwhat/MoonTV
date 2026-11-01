/* eslint-disable no-console */

import { NextRequest, NextResponse } from 'next/server';

import { getConfig } from '@/lib/config';

export const runtime = 'edge';

export async function GET(request: NextRequest) {
  console.log('server-config called: ', request.url);

  const config = await getConfig();
  const storageType = process.env.NEXT_PUBLIC_STORAGE_TYPE || 'localstorage';
  const result = {
    SiteName: config.SiteConfig.SiteName,
    StorageType: storageType,
    // Only expose the same browser-safe values injected by app/layout.tsx.
    // Credentials and TVBox passwords must never be returned by this endpoint.
    RuntimeConfig: {
      STORAGE_TYPE: storageType,
      ENABLE_REGISTER: config.UserConfig.AllowRegister,
      DOUBAN_PROXY_TYPE: config.SiteConfig.DoubanProxyType,
      DOUBAN_PROXY: config.SiteConfig.DoubanProxy,
      DOUBAN_IMAGE_PROXY_TYPE: config.SiteConfig.DoubanImageProxyType,
      DOUBAN_IMAGE_PROXY: config.SiteConfig.DoubanImageProxy,
      BANGUMI_PROXY: config.SiteConfig.BangumiProxy || '',
      BANGUMI_IMAGE_PROXY: config.SiteConfig.BangumiImageProxy || '',
      DISABLE_YELLOW_FILTER: config.SiteConfig.DisableYellowFilter,
      DANMU_API_BASE_URL: config.SiteConfig.DanmakuApiBaseUrl || '',
      PLAYBACK_SAVE_INTERVAL: config.SiteConfig.PlaybackSaveInterval,
    },
  };
  return NextResponse.json(result, {
    headers: { 'Cache-Control': 'no-store' },
  });
}
