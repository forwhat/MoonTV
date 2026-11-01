import { build } from 'esbuild';
import {
  cpSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { dirname, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const out = resolve(root, 'android/app/src/main/assets/web');
rmSync(out, { force: true, recursive: true });
mkdirSync(out, { recursive: true });

const tailwind = spawnSync(
  resolve(root, 'node_modules/.bin/tailwindcss'),
  [
    '-c',
    resolve(root, 'tailwind.config.ts'),
    '-i',
    resolve(root, 'src/app/globals.css'),
    '-o',
    resolve(out, 'app.css'),
    '--minify',
  ],
  { cwd: root, encoding: 'utf8' }
);
if (tailwind.status !== 0) {
  process.stderr.write(tailwind.stderr || tailwind.stdout);
  process.exit(tailwind.status || 1);
}

const sweetAlertCss = readFileSync(
  resolve(root, 'node_modules/sweetalert2/dist/sweetalert2.min.css'),
  'utf8'
);
writeFileSync(
  resolve(out, 'app.css'),
  `${readFileSync(resolve(out, 'app.css'), 'utf8')}\n${sweetAlertCss}`
);

const aliases = new Map([
  ['next/navigation', resolve(root, 'src/android-web/shims/navigation.ts')],
  ['next/link', resolve(root, 'src/android-web/shims/link.tsx')],
  ['next/image', resolve(root, 'src/android-web/shims/image.tsx')],
]);

await build({
  entryPoints: [resolve(root, 'src/android-web/entry.tsx')],
  outfile: resolve(out, 'app.js'),
  bundle: true,
  format: 'iife',
  platform: 'browser',
  target: ['chrome100'],
  minify: true,
  sourcemap: false,
  jsx: 'automatic',
  define: {
    'process.env': JSON.stringify({
      NODE_ENV: 'production',
      NEXT_PUBLIC_STORAGE_TYPE: 'localstorage',
    }),
  },
  plugins: [
    {
      name: 'android-web-aliases',
      setup(esbuild) {
        esbuild.onResolve(
          { filter: /^next\/(navigation|link|image)$/ },
          (args) => ({ path: aliases.get(args.path) })
        );
      },
    },
  ],
});

cpSync(resolve(root, 'src/android-web/index.html'), resolve(out, 'index.html'));
cpSync(resolve(root, 'public/icons'), resolve(out, 'icons'), {
  recursive: true,
});
cpSync(resolve(root, 'public/logo.png'), resolve(out, 'logo.png'));
cpSync(resolve(root, 'config.json'), resolve(out, 'config.json'));
