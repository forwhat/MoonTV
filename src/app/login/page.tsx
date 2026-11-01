import LoginPageClient from './LoginPageClient';

export default function LoginPage() {
  const storageType = process.env.NEXT_PUBLIC_STORAGE_TYPE || 'localstorage';

  return (
    <LoginPageClient
      initialShouldAskUsername={storageType !== 'localstorage'}
      initialEnableRegister={process.env.NEXT_PUBLIC_ENABLE_REGISTER === 'true'}
    />
  );
}
