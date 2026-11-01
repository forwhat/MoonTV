import { NextRequest, NextResponse } from 'next/server';

export const runtime = 'edge';

export async function POST(req: NextRequest) {
  const response = NextResponse.json({ ok: true });

  // 清除认证cookie
  response.cookies.set('auth', '', {
    path: '/',
    expires: new Date(0),
    sameSite: 'lax', // 改为 lax 以支持 PWA
    httpOnly: false, // PWA 需要客户端可访问
    secure:
      req.nextUrl.protocol === 'https:' ||
      req.headers.get('x-forwarded-proto') === 'https',
  });

  return response;
}
