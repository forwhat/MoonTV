import { getLoginRedirect } from '@/lib/login-redirect';

import LoginPageClient from './LoginPageClient';

export default function LoginPage({
  searchParams,
}: {
  searchParams: { error?: string; redirect?: string };
}) {
  const storageType = process.env.NEXT_PUBLIC_STORAGE_TYPE || 'localstorage';

  return (
    <LoginPageClient
      initialShouldAskUsername={storageType !== 'localstorage'}
      initialEnableRegister={process.env.NEXT_PUBLIC_ENABLE_REGISTER === 'true'}
      initialError={
        searchParams.error === 'invalid_credentials'
          ? '用户名或密码错误'
          : searchParams.error === 'server_error'
          ? '登录服务暂时不可用，请重试'
          : null
      }
      initialRedirect={getLoginRedirect(searchParams.redirect)}
    />
  );
}
