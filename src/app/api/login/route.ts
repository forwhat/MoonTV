/* eslint-disable no-console,@typescript-eslint/no-explicit-any */
import { NextRequest, NextResponse } from 'next/server';

import { getConfig } from '@/lib/config';
import { db } from '@/lib/db';
import { getLoginRedirect } from '@/lib/login-redirect';

export const runtime = 'edge';

// 读取存储类型环境变量，默认 localstorage
const STORAGE_TYPE =
  (process.env.NEXT_PUBLIC_STORAGE_TYPE as
    | 'localstorage'
    | 'redis'
    | 'upstash'
    | undefined) || 'localstorage';

// 生成签名
async function generateSignature(
  data: string,
  secret: string
): Promise<string> {
  const encoder = new TextEncoder();
  const keyData = encoder.encode(secret);
  const messageData = encoder.encode(data);

  // 导入密钥
  const key = await crypto.subtle.importKey(
    'raw',
    keyData,
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );

  // 生成签名
  const signature = await crypto.subtle.sign('HMAC', key, messageData);

  // 转换为十六进制字符串
  return Array.from(new Uint8Array(signature))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

// 生成认证Cookie（带签名）
async function generateAuthCookie(
  username?: string,
  password?: string,
  role?: 'owner' | 'admin' | 'user',
  includePassword = false
): Promise<string> {
  const authData: any = { role: role || 'user' };

  // 只在需要时包含 password
  if (includePassword && password) {
    authData.password = password;
  }

  if (username && process.env.PASSWORD) {
    authData.username = username;
    // 使用密码作为密钥对用户名进行签名
    const signature = await generateSignature(username, process.env.PASSWORD);
    authData.signature = signature;
    authData.timestamp = Date.now(); // 添加时间戳防重放攻击
  }

  return encodeURIComponent(JSON.stringify(authData));
}

function authCookieOptions(req: NextRequest, expires: Date) {
  return {
    path: '/',
    expires,
    sameSite: 'lax' as const,
    httpOnly: false,
    secure:
      req.nextUrl.protocol === 'https:' ||
      req.headers.get('x-forwarded-proto') === 'https',
  };
}

export async function POST(req: NextRequest) {
  const contentType = req.headers.get('content-type') || '';
  const isForm =
    contentType.includes('application/x-www-form-urlencoded') ||
    contentType.includes('multipart/form-data');
  let redirect = '/';
  // Native POST must work before hydration or when the TV cannot load JS.
  // JSON callers retain their existing response and cookie contract.
  const respond = (body: { ok?: boolean; error?: string }, status = 200) => {
    if (!isForm) return NextResponse.json(body, { status });
    const target = new URL(redirect, 'https://moontv.invalid');
    if (status >= 400) {
      target.pathname = '/login';
      target.search = '';
      target.searchParams.set(
        'error',
        status < 500 ? 'invalid_credentials' : 'server_error'
      );
      target.searchParams.set('redirect', redirect);
    }
    // Relative Location preserves the public origin behind reverse proxies.
    return new NextResponse(null, {
      status: 303,
      headers: {
        Location: target.pathname + target.search + target.hash,
        'Cache-Control': 'no-store',
      },
    });
  };
  try {
    const credentials = isForm
      ? Object.fromEntries(await req.formData())
      : await req.json();
    redirect = getLoginRedirect(credentials.redirect);
    // 本地 / localStorage 模式——仅校验固定密码
    if (STORAGE_TYPE === 'localstorage') {
      const envPassword = process.env.PASSWORD;

      // 未配置 PASSWORD 时直接放行
      if (!envPassword) {
        const response = respond({ ok: true });

        // 清除可能存在的认证cookie
        response.cookies.set('auth', '', authCookieOptions(req, new Date(0)));

        return response;
      }

      const { password } = credentials;
      if (typeof password !== 'string') {
        return respond({ error: '密码不能为空' }, 400);
      }

      if (password !== envPassword) {
        return respond({ ok: false, error: '密码错误' }, 401);
      }

      // 验证成功，设置认证cookie
      const response = respond({ ok: true });
      const cookieValue = await generateAuthCookie(
        undefined,
        password,
        'user',
        true
      ); // localstorage 模式包含 password
      const expires = new Date();
      expires.setDate(expires.getDate() + 7); // 7天过期

      response.cookies.set(
        'auth',
        cookieValue,
        authCookieOptions(req, expires)
      );

      return response;
    }

    // 数据库 / redis 模式——校验用户名并尝试连接数据库
    const { username, password } = credentials;

    if (!username || typeof username !== 'string') {
      return respond({ error: '用户名不能为空' }, 400);
    }
    if (!password || typeof password !== 'string') {
      return respond({ error: '密码不能为空' }, 400);
    }

    // 可能是站长，直接读环境变量
    if (
      username === process.env.USERNAME &&
      password === process.env.PASSWORD
    ) {
      // 验证成功，设置认证cookie
      const response = respond({ ok: true });
      const cookieValue = await generateAuthCookie(
        username,
        password,
        'owner',
        false
      ); // 数据库模式不包含 password
      const expires = new Date();
      expires.setDate(expires.getDate() + 7); // 7天过期

      response.cookies.set(
        'auth',
        cookieValue,
        authCookieOptions(req, expires)
      );

      return response;
    } else if (username === process.env.USERNAME) {
      return respond({ error: '用户名或密码错误' }, 401);
    }

    const config = await getConfig();
    const user = config.UserConfig.Users.find((u) => u.username === username);
    if (user && user.banned) {
      return respond({ error: '用户被封禁' }, 401);
    }

    // 校验用户密码
    try {
      const pass = await db.verifyUser(username, password);
      if (!pass) {
        return respond({ error: '用户名或密码错误' }, 401);
      }

      // 验证成功，设置认证cookie
      const response = respond({ ok: true });
      const cookieValue = await generateAuthCookie(
        username,
        password,
        user?.role || 'user',
        false
      ); // 数据库模式不包含 password
      const expires = new Date();
      expires.setDate(expires.getDate() + 7); // 7天过期

      response.cookies.set(
        'auth',
        cookieValue,
        authCookieOptions(req, expires)
      );

      return response;
    } catch (err) {
      console.error('数据库验证失败', err);
      return respond({ error: '数据库错误' }, 500);
    }
  } catch (error) {
    console.error('登录接口异常', error);
    return respond({ error: '服务器错误' }, 500);
  }
}
