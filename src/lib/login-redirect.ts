export function getLoginRedirect(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/')) return '/';
  const base = 'https://moontv.invalid';
  try {
    const target = new URL(value, base);
    return target.origin === base
      ? target.pathname + target.search + target.hash
      : '/';
  } catch {
    return '/';
  }
}
