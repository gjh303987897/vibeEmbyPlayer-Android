#!/usr/bin/env node
// Exercise the packaged FFmpeg integer parser on a connected Android device.
// Requires the pinned native sources/generated headers from build-ijkplayer-native.mjs.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const options = {};
for (let i = 2; i < process.argv.length; i += 2) {
  if (!process.argv[i].startsWith('--') || !process.argv[i + 1]) throw Error('Expected --option value');
  options[process.argv[i].slice(2)] = process.argv[i + 1];
}
const run = (program, args, cwd = root) => {
  const result = spawnSync(program, args, { cwd, stdio: 'inherit', shell: false });
  if (result.error) throw result.error;
  if (result.status !== 0) throw Error(`${program} exited ${result.status}`);
};
const capture = (program, args) => {
  const result = spawnSync(program, args, { cwd: root, encoding: 'utf8', shell: false });
  if (result.error || result.status !== 0) throw result.error || Error(result.stderr);
  return result.stdout.trim();
};
const windows = process.platform === 'win32';
const exe = windows ? '.exe' : '';
const adb = options.adb || `adb${exe}`;
const abi = options.abi || capture(adb, ['shell', 'getprop', 'ro.product.cpu.abi']);
const targets = {
  'arm64-v8a': ['arm64', 'aarch64-linux-android26'],
  'armeabi-v7a': ['armv7a', 'armv7a-linux-androideabi26'],
  'x86': ['x86', 'i686-linux-android26'],
  'x86_64': ['x86_64', 'x86_64-linux-android26'],
};
if (!targets[abi]) throw Error(`Unsupported ABI: ${abi}`);
const [name, target] = targets[abi];
const ndk = path.resolve(options.ndk || process.env.ANDROID_NDK_HOME || process.env.ANDROID_NDK || '');
if (!fs.existsSync(path.join(ndk, 'source.properties'))) throw Error('Pass --ndk with the installed NDK root');
const host = windows ? 'windows-x86_64' : process.platform === 'darwin' ? 'darwin-x86_64' : 'linux-x86_64';
const prebuilt = path.join(ndk, 'toolchains/llvm/prebuilt', host);
const source = path.join(root, 'build/ijk-native/source/ffmpeg');
const generated = path.join(root, 'build/ijk-native', name);
if (!fs.existsSync(path.join(generated, 'libavutil/avconfig.h'))) {
  throw Error(`Rebuild native sources for ${name} first to generate the matching FFmpeg headers`);
}
const location = path.join(root, 'build/ijk-int64-test', name);
fs.mkdirSync(location, { recursive: true });
const jar = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', `jar${exe}`) : `jar${exe}`;
run(jar, ['--extract', '--file', path.join(root, 'third_party/ijkplayer', `ijkplayer-${name}-0.8.8-full.aar`),
  `jni/${abi}/libijkffmpeg.so`], location);
const library = path.join(location, 'jni', abi);
const binary = path.join(location, 'int64_options_test');
run(path.join(prebuilt, 'bin', `clang${exe}`), [`--target=${target}`, `--sysroot=${path.join(prebuilt, 'sysroot')}`,
  '-fPIE', '-pie', '-Wall', '-Wextra', '-Wno-missing-field-initializers', `-I${generated}`, `-I${source}`,
  path.join(root, 'third_party/ijkplayer/tests/int64_options_test.c'), `-L${library}`, '-lijkffmpeg', '-o', binary]);
const remote = '/data/local/tmp/vibeplayer-int64-regression';
run(adb, ['shell', 'mkdir', '-p', remote]);
run(adb, ['push', binary, path.join(library, 'libijkffmpeg.so'), `${remote}/`]);
run(adb, ['shell', `cd ${remote} && chmod 700 int64_options_test && LD_LIBRARY_PATH=. ./int64_options_test`]);
