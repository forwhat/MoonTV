import { Suspense } from 'react';

import PlayClient from './PlayClient';

/**
 * 播放页入口。
 *
 * 完整逻辑已拆分至同目录下的 usePlayEngine / PlayClient 及
 * components / hooks / play-utils 模块，此文件仅保留 Suspense 包装。
 */
export default function PlayPage() {
  return (
    <Suspense
      fallback={
        <div className='flex min-h-screen items-center justify-center bg-slate-50 text-slate-600 dark:bg-slate-950 dark:text-slate-300'>
          <div className='flex flex-col items-center gap-4'>
            <div className='h-10 w-10 animate-spin rounded-full border-4 border-green-500/25 border-t-green-500' />
            <p className='text-sm'>正在加载播放页...</p>
          </div>
        </div>
      }
    >
      <PlayClient />
    </Suspense>
  );
}
