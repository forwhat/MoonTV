import React, { Suspense, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';

import { getAuthInfoFromBrowserCookie } from '@/lib/auth';

import ConditionalNav from '@/components/ConditionalNav';
import { GlobalErrorIndicator } from '@/components/GlobalErrorIndicator';
import { NavigationLoadingIndicator } from '@/components/NavigationLoadingIndicator';
import { NavigationLoadingProvider } from '@/components/NavigationLoadingProvider';
import { SiteProvider } from '@/components/SiteProvider';
import { ThemeProvider } from '@/components/ThemeProvider';

import AdminPage from '@/app/admin/page';
import DoubanPage from '@/app/douban/page';
import LoginPageClient from '@/app/login/LoginPageClient';
import Home from '@/app/page';
import PlayPage from '@/app/play/page';
import RankingPage from '@/app/ranking/page';
import SearchPage from '@/app/search/page';

declare global {
  interface Window {
    RUNTIME_CONFIG?: Record<string, unknown>;
  }
}

window.RUNTIME_CONFIG ||= { STORAGE_TYPE: 'localstorage' };

function routeComponent(pathname: string, storageType: string) {
  if (pathname.startsWith('/login')) {
    return (
      <LoginPageClient
        initialShouldAskUsername={storageType !== 'localstorage'}
        initialEnableRegister={Boolean(window.RUNTIME_CONFIG?.ENABLE_REGISTER)}
        initialError={null}
        initialRedirect='/'
      />
    );
  }
  if (pathname.startsWith('/admin')) return <AdminPage />;
  if (pathname.startsWith('/play')) return <PlayPage />;
  if (pathname.startsWith('/search')) return <SearchPage />;
  if (pathname.startsWith('/douban')) return <DoubanPage />;
  if (pathname.startsWith('/ranking')) return <RankingPage />;
  return <Home />;
}

function App() {
  const [locationKey, setLocationKey] = useState(
    () => `${window.location.pathname}${window.location.search}`
  );
  const [booting, setBooting] = useState(true);
  const [bootError, setBootError] = useState('');
  const [siteName, setSiteName] = useState('MoonTV');
  const [storageType, setStorageType] = useState(() =>
    String(window.RUNTIME_CONFIG?.STORAGE_TYPE || 'localstorage')
  );

  useEffect(() => {
    const bootstrap = async () => {
      try {
        const [runtimeResponse, configResponse] = await Promise.all([
          fetch('/api/native/runtime', { cache: 'no-store' }),
          fetch('/api/server-config', { cache: 'no-store' }),
        ]);
        if (!runtimeResponse.ok || !configResponse.ok) {
          throw new Error(
            `HTTP ${runtimeResponse.status}/${configResponse.status}`
          );
        }
        const runtime = await runtimeResponse.json();
        const serverConfig = await configResponse.json();
        const publicRuntimeConfig =
          serverConfig.RuntimeConfig || serverConfig.SiteConfig || {};
        const nextStorageType = String(
          serverConfig.StorageType ||
            publicRuntimeConfig.STORAGE_TYPE ||
            window.RUNTIME_CONFIG?.STORAGE_TYPE ||
            'localstorage'
        );
        Object.assign(window.RUNTIME_CONFIG || {}, publicRuntimeConfig, {
          STORAGE_TYPE: nextStorageType,
          ANDROID_MODE: runtime.mode,
          ANDROID_LOCAL_FIRST: true,
        });
        setStorageType(nextStorageType);
        setSiteName(serverConfig.SiteName || 'MoonTV');
        if (
          runtime.mode === 'server' &&
          !getAuthInfoFromBrowserCookie() &&
          window.location.pathname !== '/login'
        ) {
          window.history.replaceState({}, '', '/login');
          window.dispatchEvent(new Event('moontv-route-change'));
        }
      } catch (error) {
        setBootError(
          error instanceof Error ? error.message : '运行环境加载失败'
        );
      } finally {
        setBooting(false);
      }
    };
    void bootstrap();
  }, []);

  useEffect(() => {
    const onRoute = () => {
      setLocationKey(`${window.location.pathname}${window.location.search}`);
      window.scrollTo({ top: 0 });
    };
    window.addEventListener('popstate', onRoute);
    window.addEventListener('moontv-route-change', onRoute);
    return () => {
      window.removeEventListener('popstate', onRoute);
      window.removeEventListener('moontv-route-change', onRoute);
    };
  }, []);

  if (booting) {
    return (
      <div className='flex min-h-screen items-center justify-center bg-black text-gray-300'>
        <div className='flex items-center gap-3'>
          <div className='h-6 w-6 animate-spin rounded-full border-2 border-green-500/30 border-t-green-500' />
          正在启动 MoonTV…
        </div>
      </div>
    );
  }

  if (bootError) {
    return (
      <div className='flex min-h-screen items-center justify-center bg-black px-6 text-gray-200'>
        <div className='max-w-md rounded-2xl border border-gray-800 bg-gray-950 p-6 text-center'>
          <h1 className='text-xl font-semibold'>页面加载失败</h1>
          <p className='mt-3 break-all text-sm text-gray-400'>{bootError}</p>
          <div className='mt-5 flex justify-center gap-3'>
            <button
              type='button'
              onClick={() => window.location.reload()}
              className='rounded-lg bg-green-600 px-4 py-2 text-white'
            >
              重新加载
            </button>
            <button
              type='button'
              onClick={() =>
                window.MoonTVNative?.postMessage(
                  JSON.stringify({
                    version: 1,
                    type: 'action',
                    action: 'open-settings',
                  })
                )
              }
              className='rounded-lg bg-gray-800 px-4 py-2 text-white'
            >
              切换模式
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <ThemeProvider attribute='class' defaultTheme='system' enableSystem>
      <NavigationLoadingProvider>
        <SiteProvider siteName={siteName} announcement={undefined}>
          <NavigationLoadingIndicator />
          <ConditionalNav />
          <div className='relative w-full'>
            <main
              className='mb-14 flex-1 md:mb-0'
              style={{
                paddingBottom: 'calc(3.5rem + env(safe-area-inset-bottom))',
              }}
            >
              <Suspense fallback={null} key={locationKey}>
                {routeComponent(window.location.pathname, storageType)}
              </Suspense>
            </main>
          </div>
          <GlobalErrorIndicator />
        </SiteProvider>
      </NavigationLoadingProvider>
    </ThemeProvider>
  );
}

document.documentElement.lang = 'zh-CN';
document.body.className =
  'min-h-screen bg-white text-gray-900 dark:bg-black dark:text-gray-200';

const container = document.getElementById('root');
if (!container) throw new Error('Missing Android web root');
createRoot(container).render(<App />);
