import { useMemo, useSyncExternalStore } from 'react';

function subscribe(callback: () => void) {
  window.addEventListener('popstate', callback);
  window.addEventListener('moontv-route-change', callback);
  return () => {
    window.removeEventListener('popstate', callback);
    window.removeEventListener('moontv-route-change', callback);
  };
}

function snapshot() {
  return `${window.location.pathname}${window.location.search}`;
}

function navigate(url: string, replace = false) {
  const target = new URL(url, window.location.href);
  if (target.origin !== window.location.origin) {
    window.location.href = target.toString();
    return;
  }
  if (replace) window.history.replaceState({}, '', target.toString());
  else window.history.pushState({}, '', target.toString());
  window.dispatchEvent(new Event('moontv-route-change'));
}

export function usePathname() {
  useSyncExternalStore(subscribe, snapshot, snapshot);
  return window.location.pathname;
}

export function useSearchParams() {
  const value = useSyncExternalStore(subscribe, snapshot, snapshot);
  return useMemo(
    () => new URLSearchParams(value.split('?')[1] || ''),
    [value]
  );
}

export function useRouter() {
  useSyncExternalStore(subscribe, snapshot, snapshot);
  return {
    push: (url: string) => navigate(url),
    replace: (url: string) => navigate(url, true),
    back: () => window.history.back(),
    forward: () => window.history.forward(),
    refresh: () => window.location.reload(),
    prefetch: async () => undefined,
  };
}
