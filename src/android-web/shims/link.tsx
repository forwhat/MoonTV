import React from 'react';

type Props = Omit<React.AnchorHTMLAttributes<HTMLAnchorElement>, 'href'> & {
  href: string | { pathname?: string; query?: Record<string, unknown> };
  replace?: boolean;
};

function toHref(href: Props['href']) {
  if (typeof href === 'string') return href;
  const params = new URLSearchParams();
  Object.entries(href.query || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null) params.set(key, String(value));
  });
  const query = params.toString();
  return `${href.pathname || '/'}${query ? `?${query}` : ''}`;
}

export default function Link({ href, replace, onClick, ...props }: Props) {
  const target = toHref(href);
  return (
    <a
      {...props}
      href={target}
      onClick={(event) => {
        onClick?.(event);
        if (
          event.defaultPrevented ||
          event.button !== 0 ||
          event.metaKey ||
          event.ctrlKey ||
          event.shiftKey ||
          event.altKey
        ) {
          return;
        }
        const url = new URL(target, window.location.href);
        if (url.origin !== window.location.origin) return;
        event.preventDefault();
        if (replace) window.history.replaceState({}, '', url.toString());
        else window.history.pushState({}, '', url.toString());
        window.dispatchEvent(new Event('moontv-route-change'));
      }}
    />
  );
}
