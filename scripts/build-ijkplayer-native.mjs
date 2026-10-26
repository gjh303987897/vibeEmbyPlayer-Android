#!/usr/bin/env node
// Rebuild all three native libraries from pinned source with 16KB ELF alignment.
// Windows requires Git Bash, GNU Make >=4, CMake, Ninja, Node >=18.14, a host C compiler,
// JDK jar and NDK r26b. Linux/macOS use Bash and their native NDK toolchain.
// Usage: node scripts/build-ijkplayer-native.mjs --ndk <root> --bash <bash.exe>
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const options = {};
for (let i = 2; i < process.argv.length; i += 2) {
  if (!process.argv[i].startsWith('--') || !process.argv[i + 1]) throw Error('Expected --option value');
  options[process.argv[i].slice(2)] = process.argv[i + 1];
}
const ndk = path.resolve(options.ndk || process.env.ANDROID_NDK_HOME || process.env.ANDROID_NDK || '');
if (!fs.existsSync(path.join(ndk, 'source.properties'))) throw Error('Pass --ndk with the actual installed NDK root');
if (!fs.readFileSync(path.join(ndk, 'source.properties'), 'utf8').includes('26.1.10909125')) throw Error('This recipe requires NDK r26b (26.1.10909125)');
const windows = process.platform === 'win32';
const host = windows ? 'windows-x86_64' : process.platform === 'darwin' ? 'darwin-x86_64' : 'linux-x86_64';
const toolchain = path.join(ndk, 'toolchains/llvm/prebuilt', host, 'bin');
const bash = options.bash || 'bash';
const jobs = Number(options.jobs || Math.min(os.availableParallelism(), 8));
const python = options.python || 'python';
// A local Meson module directory may be used without installing system packages.
const mesonEnv = options['meson-path'] ? { PYTHONPATH: path.resolve(options['meson-path']) } : {};
const meson = args => run(python, ['-m', 'mesonbuild.mesonmain', ...args], root, mesonEnv);
const build = path.join(root, 'build/ijk-native');
const vendor = path.join(root, 'third_party/ijkplayer');
const source = path.join(build, 'source');
const forward = value => value.replaceAll('\\', '/');
const quote = value => `'${String(value).replaceAll("'", "'\\''")}'`;
const run = (program, args, cwd = root, env = {}) => {
  console.log(`Running ${program} ${args.join(' ')}`);
  const result = spawnSync(program, args, { cwd, env: { ...process.env, ...env }, stdio: 'inherit', shell: false });
  if (result.error) throw result.error;
  if (result.status !== 0) throw Error(`${program} exited ${result.status}`);
};
const capture = (program, args, cwd = root) => {
  const result = spawnSync(program, args, { cwd, encoding: 'utf8', shell: false });
  if (result.error || result.status !== 0) throw result.error || Error(result.stderr);
  return result.stdout.trim();
};
const shell = (args, cwd) => run(bash, ['-c', args.map(quote).join(' ')], cwd);
const sha256 = filename => crypto.createHash('sha256').update(fs.readFileSync(filename)).digest('hex');
fs.mkdirSync(source, { recursive: true });
const repos = [
  { name: 'dav1d', url: 'https://code.videolan.org/videolan/dav1d.git', tag: '1.5.4', commit: '54706fc6bc0cdecab7e9593974a4039cc038fca7', license: 'BSD-2-Clause' },
  { name: 'ffmpeg', url: 'https://github.com/Bilibili/FFmpeg.git', tag: 'ff3.4--ijk0.8.7--20180103--001', commit: '2902e33f6e59ed77ddc1b372586c1ab8db1fb096', license: 'LGPL-2.1-or-later' },
  { name: 'libxml2', url: 'https://github.com/GNOME/libxml2.git', tag: 'v2.12.10', commit: '02d577715e1951cf114ca4e9adaf5605156c4f4c', license: 'MIT' },
  { name: 'ijkplayer', url: 'https://github.com/bilibili/ijkplayer.git', tag: 'k0.8.8', commit: 'cced91e3ae3730f5c63f3605b00d25eafcf5b97b', license: 'LGPL-2.1-or-later' },
  { name: 'libyuv', url: 'https://github.com/Bilibili/libyuv.git', tag: 'ijk-r0.2.1-dev', commit: 'a0c7dd3e4b095649881f39152655437b8cde1589', license: 'BSD-3-Clause' },
  { name: 'soundtouch', url: 'https://github.com/Bilibili/soundtouch.git', tag: 'ijk-r0.1.2-dev', commit: '6bf39cd3bf6b0c156267d12446b0d6bdcfcd53c2', license: 'LGPL-2.1-or-later' },
];
for (const repo of repos) {
  const location = path.join(source, repo.name);
  if (!fs.existsSync(location)) {
    fs.mkdirSync(location, { recursive: true });
    run('git', ['init', location]);
    run('git', ['remote', 'add', 'origin', repo.url], location);
    run('git', ['fetch', '--depth', '1', 'origin', repo.commit], location);
    run('git', ['checkout', '--detach', 'FETCH_HEAD'], location);
  }
  if (capture('git', ['rev-parse', 'HEAD'], location) !== repo.commit) throw Error(`Unexpected ${repo.name} source revision`);
}
const ffmpeg = path.join(source, 'ffmpeg');
const patch = path.join(vendor, 'ffmpeg-ndk26.patch');
const patchCheck = spawnSync('git', ['apply', '--reverse', '--check', patch], { cwd: ffmpeg, stdio: 'ignore' });
if (patchCheck.status !== 0) run('git', ['apply', patch], ffmpeg);
const int64Patch = path.join(vendor, 'ffmpeg-int64-options.patch');
if (spawnSync('git', ['apply', '--reverse', '--check', int64Patch], { cwd: ffmpeg, stdio: 'ignore' }).status !== 0) {
  run('git', ['apply', int64Patch], ffmpeg);
}
const av1Patch = path.join(vendor, 'ffmpeg-dav1d.patch');
if (spawnSync('git', ['apply', '--reverse', '--check', av1Patch], { cwd: ffmpeg, stdio: 'ignore' }).status !== 0) {
  run('git', ['apply', av1Patch], ffmpeg);
}
const wrapper = path.join(vendor, 'libdav1d.c');
const wrapperOutput = path.join(ffmpeg, 'libavcodec/libdav1d.c');
if (!fs.existsSync(wrapperOutput) || sha256(wrapperOutput) !== sha256(wrapper)) fs.copyFileSync(wrapper, wrapperOutput);
const ijkSource = path.join(source, 'ijkplayer');
const audioPresentationPatch = path.join(vendor, 'ijkplayer-audio-presentation.patch');
// This outer overlay uses the PCM timing changes below; unwind it before older layers.
if (spawnSync('git', ['apply', '--reverse', '--check', audioPresentationPatch], { cwd: ijkSource, stdio: 'ignore' }).status === 0) {
  run('git', ['apply', '--reverse', audioPresentationPatch], ijkSource);
}
const playbackTimingPatch = path.join(vendor, 'ijkplayer-playback-timing.patch');
// Unwind overlays before checking their underlying context, then reapply in order.
if (spawnSync('git', ['apply', '--reverse', '--check', playbackTimingPatch], { cwd: ijkSource, stdio: 'ignore' }).status === 0) {
  run('git', ['apply', '--reverse', playbackTimingPatch], ijkSource);
}
const subtitleDelayPatch = path.join(vendor, 'ijkplayer-subtitle-delay.patch');
// This final overlay touches context from the bitmap patch. Validate older layers
// against their original context, then reapply the timing overlay deterministically.
if (spawnSync('git', ['apply', '--reverse', '--check', subtitleDelayPatch], { cwd: ijkSource, stdio: 'ignore' }).status === 0) {
  run('git', ['apply', '--reverse', subtitleDelayPatch], ijkSource);
}
const ijkPatch = path.join(vendor, 'ijkplayer-ndk26.patch');
if (spawnSync('git', ['apply', '--reverse', '--check', ijkPatch], { cwd: ijkSource, stdio: 'ignore' }).status !== 0) {
  run('git', ['apply', ijkPatch], ijkSource);
}
const bitmapPatch = path.join(vendor, 'ijkplayer-bitmap-subtitles.patch');
if (spawnSync('git', ['apply', '--reverse', '--check', bitmapPatch], { cwd: ijkSource, stdio: 'ignore' }).status !== 0) {
  run('git', ['apply', bitmapPatch], ijkSource);
}
if (spawnSync('git', ['apply', '--reverse', '--check', subtitleDelayPatch], { cwd: ijkSource, stdio: 'ignore' }).status !== 0) {
  run('git', ['apply', subtitleDelayPatch], ijkSource);
}
run('git', ['apply', playbackTimingPatch], ijkSource);
run('git', ['apply', audioPresentationPatch], ijkSource);
const abis = {
  armv7a: { android: 'armeabi-v7a', arch: 'arm', target: 'armv7a-linux-androideabi', extra: ['--cpu=armv7-a', '--enable-neon'] },
  arm64: { android: 'arm64-v8a', arch: 'aarch64', target: 'aarch64-linux-android', extra: [] },
  // Legacy i686 inline asm exceeds Clang's register constraints; its NASM kernels
  // contain absolute text relocations rejected by Android's modern linker.
  x86: { android: 'x86', arch: 'x86', target: 'i686-linux-android', extra: ['--cpu=i686', '--disable-inline-asm', '--disable-x86asm'] },
  x86_64: { android: 'x86_64', arch: 'x86_64', target: 'x86_64-linux-android', extra: [] },
};
const selected = options.abi ? options.abi.split(',') : Object.keys(abis);
const exe = windows ? '.exe' : '';
const jar = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', `jar${exe}`) : 'jar';
const metadata = {
  nativeLibraries: ['libijkffmpeg.so', 'libijkplayer.so', 'libijksdl.so'],
  sources: repos, ndk: '26.1.10909125', androidApi: 21, patchSha256: sha256(patch), ijkPatchSha256: sha256(ijkPatch),
  int64Options: {
    patchSha256: sha256(int64Patch),
    regressionSha256: sha256(path.join(vendor, 'tests/int64_options_test.c')),
  },
  bitmapSubtitles: {
    patchSha256: sha256(bitmapPatch),
    supportSha256: Object.fromEntries(['bitmap_subtitles.c', 'bitmap_subtitles.h', 'bitmap_subtitle_pixels.c', 'bitmap_subtitle_pixels.h']
      .map(file => [file, sha256(path.join(vendor, file))])),
    jniExport: 'Java_com_vibeplayer_app_player_NativeBitmapSubtitles_read',
  },
  nativeTls: false, tlsTransport: 'Application HTTP loopback proxy', artifacts: {},
  subtitleTiming: {
    patchSha256: sha256(subtitleDelayPatch),
    jniExport: 'Java_com_vibeplayer_app_player_NativeBitmapSubtitles_setDelay',
    maxDelayMs: 10000,
  },
  av1: { patchSha256: sha256(av1Patch), wrapperSha256: sha256(path.join(vendor, 'libdav1d.c')) },
  playbackTiming: {
    patchSha256: sha256(playbackTimingPatch),
    supportSha256: Object.fromEntries(['playback_timing.h', 'playback_soundtouch.cpp']
      .map(file => [file, sha256(path.join(vendor, file))])),
  },
  audioPresentation: {
    patchSha256: sha256(audioPresentationPatch),
    supportSha256: Object.fromEntries(['audio_presentation_clock.h', 'audio_presentation_jni.h', 'audio_presentation_jni.c']
      .map(file => [file, sha256(path.join(vendor, file))])),
    regressionSha256: sha256(path.join(vendor, 'tests/audio_presentation_clock_test.c')),
    sourceProperty: 10901,
    mediaDelayProperty: 10902,
    displayRefreshProperty: 10903,
    jniExports: ['Java_com_vibeplayer_app_player_NativePlaybackTiming_setDisplayRefreshRate',
      'Java_com_vibeplayer_app_player_NativePlaybackTiming_readFloat'],
  },
};
// Separate metadata files allow independent ABI jobs without sharing writable outputs.
const prior = options.metadata ? path.resolve(options.metadata) : path.join(vendor, 'native-build.json');
const seed = fs.existsSync(prior) ? prior : path.join(vendor, 'native-build.json');
if (fs.existsSync(seed)) Object.assign(metadata.artifacts, JSON.parse(fs.readFileSync(seed, 'utf8')).artifacts);
for (const name of selected) {
  const abi = abis[name];
  if (!abi) throw Error(`Unknown ABI ${name}`);
  const location = path.join(build, name);
  const xmlBuild = path.join(location, 'xml');
  const xmlOutput = path.join(location, 'xml-output');
  fs.mkdirSync(location, { recursive: true });
  run('cmake', ['-S', path.join(source, 'libxml2'), '-B', xmlBuild, '-G', 'Ninja',
    `-DCMAKE_TOOLCHAIN_FILE=${forward(path.join(ndk, 'build/cmake/android.toolchain.cmake'))}`,
    `-DANDROID_ABI=${abi.android}`, '-DANDROID_PLATFORM=android-21', '-DCMAKE_BUILD_TYPE=Release',
    `-DCMAKE_INSTALL_PREFIX=${forward(xmlOutput)}`, '-DCMAKE_POSITION_INDEPENDENT_CODE=ON', '-DBUILD_SHARED_LIBS=OFF',
    ...['PROGRAMS', 'PYTHON', 'TESTS', 'HTTP', 'FTP', 'ICONV', 'LZMA', 'ZLIB', 'MODULES'].map(feature => `-DLIBXML2_WITH_${feature}=OFF`)]);
  run('cmake', ['--build', xmlBuild, '--parallel', String(jobs)]);
  run('cmake', ['--install', xmlBuild]);
  const compiler = forward(path.join(toolchain, `${abi.target}21-clang`));
  const dav1dBuild = path.join(location, 'dav1d');
  const dav1dOutput = path.join(location, 'dav1d-output');
  const crossFile = path.join(location, 'dav1d-cross.ini');
  fs.writeFileSync(crossFile, `[binaries]\nc = ['${forward(path.join(toolchain, `clang${exe}`))}', '--target=${abi.target}21']\nar = '${forward(path.join(toolchain, `llvm-ar${exe}`))}'\nstrip = '${forward(path.join(toolchain, `llvm-strip${exe}`))}'\n[host_machine]\nsystem = 'android'\ncpu_family = '${abi.arch === 'x86' ? 'x86' : abi.arch}'\ncpu = '${name === 'armv7a' ? 'armv7-a' : abi.arch}'\nendian = 'little'\n[properties]\nneeds_exe_wrapper = true\n`);
  meson(['setup', ...(fs.existsSync(path.join(dav1dBuild, 'build.ninja')) ? ['--reconfigure'] : []),
    dav1dBuild, path.join(source, 'dav1d'), '--cross-file', crossFile, '--prefix', dav1dOutput,
    '--libdir=lib', '--buildtype=release', '--default-library=static', '-Db_staticpic=true',
    '-Denable_tools=false', '-Denable_tests=false', '-Denable_examples=false', '-Denable_docs=false']);
  run('ninja', ['-C', dav1dBuild, `-j${jobs}`], root, mesonEnv);
  meson(['install', '-C', dav1dBuild]);
  const flags = [
    '--prefix=output', '--target-os=android', `--arch=${abi.arch}`, ...abi.extra, '--enable-cross-compile',
    `--cc=${compiler}`, `--cxx=${compiler}++`, `--ar=${forward(path.join(toolchain, `llvm-ar${exe}`))}`,
    `--ranlib=${forward(path.join(toolchain, `llvm-ranlib${exe}`))}`, `--nm=${forward(path.join(toolchain, `llvm-nm${exe}`))}`,
    `--strip=${forward(path.join(toolchain, `llvm-strip${exe}`))}`, `--host-cc=${options['host-cc'] || 'clang'}`,
    '--enable-pic', '--disable-gpl', '--disable-nonfree', '--disable-autodetect', '--disable-programs', '--disable-doc',
    '--disable-avdevice', '--disable-postproc', '--disable-avresample', '--disable-encoders', '--disable-muxers',
    '--disable-devices', '--disable-iconv', '--disable-debug', '--enable-libxml2', '--enable-zlib', '--enable-libdav1d',
    `--extra-cflags=-DANDROID -I${forward(path.join(xmlOutput, 'include/libxml2'))} -I${forward(path.join(dav1dOutput, 'include'))}`,
    `--extra-ldflags=-L${forward(path.join(xmlOutput, 'lib'))} -L${forward(path.join(dav1dOutput, 'lib'))}`,
  ];
  const configuration = JSON.stringify({ flags, patch: metadata.patchSha256, int64Patch: metadata.int64Options.patchSha256, av1Patch: metadata.av1.patchSha256 });
  const savedConfiguration = path.join(location, 'configure-arguments.json');
  if (!fs.existsSync(savedConfiguration) || fs.readFileSync(savedConfiguration, 'utf8') !== configuration) {
    shell(['bash', forward(path.join(ffmpeg, 'configure')), ...flags], location);
    fs.writeFileSync(savedConfiguration, configuration);
  }
  // Reject reduced configure results before producing an archive.
  const config = fs.readFileSync(path.join(location, 'config.h'), 'utf8');
  const required = ['LIBDAV1D_DECODER', 'AC3_DECODER', 'EAC3_DECODER', 'DCA_DECODER', 'TRUEHD_DECODER', 'ASS_DECODER', 'SSA_DECODER',
    'SUBRIP_DECODER', 'WEBVTT_DECODER', 'MOVTEXT_DECODER', 'DVDSUB_DECODER', 'DVBSUB_DECODER', 'PGSSUB_DECODER',
    'MATROSKA_DEMUXER', 'MOV_DEMUXER', 'HLS_DEMUXER', 'DASH_DEMUXER', 'CRYPTO_PROTOCOL', 'HTTP_PROTOCOL'];
  for (const feature of required) if (!config.includes(`#define CONFIG_${feature} 1`)) throw Error(`Missing ${feature} on ${name}`);
  shell(['make', `-j${jobs}`, `SHELL=${forward(bash)}`], location);
  const libraries = ['avfilter', 'avformat', 'avcodec', 'swresample', 'swscale', 'avutil'];
  const native = path.join(location, 'libijkffmpeg.so');
  shell([compiler, '-shared', '-Wl,--no-undefined', '-Wl,-z,noexecstack', '-Wl,-z,max-page-size=16384',
    '-Wl,-Bsymbolic', '-Wl,-soname,libijkffmpeg.so', '-Wl,--whole-archive', ...libraries.map(library => `lib${library}/lib${library}.a`),
    '-Wl,--no-whole-archive', forward(path.join(xmlOutput, 'lib/libxml2.a')), forward(path.join(dav1dOutput, 'lib/libdav1d.a')), '-lm', '-lz', '-ldl', '-llog', '-latomic', '-o', forward(native)], location);
  run(path.join(toolchain, `llvm-strip${exe}`), ['--strip-unneeded', native]);
  const core = path.join(location, 'core');
  run('cmake', ['-S', vendor, '-B', core, '-G', 'Ninja',
    `-DCMAKE_TOOLCHAIN_FILE=${forward(path.join(ndk, 'build/cmake/android.toolchain.cmake'))}`,
    `-DANDROID_ABI=${abi.android}`, '-DANDROID_PLATFORM=android-21', '-DCMAKE_BUILD_TYPE=Release', '-DANDROID_STL=c++_static',
    `-DIJK_SOURCE=${forward(ijkSource)}`, `-DFFMPEG_SOURCE=${forward(ffmpeg)}`, `-DFFMPEG_BUILD=${forward(location)}`,
    `-DLIBYUV_SOURCE=${forward(path.join(source, 'libyuv'))}`, `-DSOUNDTOUCH_SOURCE=${forward(path.join(source, 'soundtouch'))}`]);
  run('cmake', ['--build', core, '--parallel', String(jobs)]);
  const stage = path.join(location, 'package');
  const entries = [];
  const nativeHashes = {};
  for (const library of metadata.nativeLibraries) {
    const filename = library === 'libijkffmpeg.so' ? native : path.join(core, library);
    run(path.join(toolchain, `llvm-strip${exe}`), ['--strip-unneeded', filename]);
    const entry = `jni/${abi.android}/${library}`;
    fs.mkdirSync(path.dirname(path.join(stage, entry)), { recursive: true });
    fs.copyFileSync(filename, path.join(stage, entry));
    entries.push('-C', stage, entry);
    nativeHashes[library] = sha256(filename);
  }
  const input = path.join(vendor, `ijkplayer-${name}-0.8.8.aar`);
  const output = path.join(vendor, `ijkplayer-${name}-0.8.8-full.aar`);
  // Gradle can retain a mapped ZipFile on Windows after its build completes.
  // Publish a complete archive by rename instead of truncating that mapped file.
  const stagedOutput = path.join(location, path.basename(output));
  fs.copyFileSync(input, stagedOutput);
  run(jar, ['--update', '--file', stagedOutput, '--no-manifest', ...entries]);
  fs.renameSync(stagedOutput, output);
  fs.copyFileSync(path.join(location, 'config.h'), path.join(vendor, `ffmpeg-${name}-config.h`));
  metadata.artifacts[name] = { file: path.basename(output), sha256: sha256(output), upstreamAarSha256: sha256(input), nativeHashes, configureArguments: flags, requiredFeatures: required };
  fs.writeFileSync(`${prior}.tmp`, `${JSON.stringify(metadata, null, 2)}\n`);
  fs.renameSync(`${prior}.tmp`, prior);
}
fs.copyFileSync(path.join(source, 'libxml2/Copyright'), path.join(vendor, 'COPYING.libxml2'));
fs.copyFileSync(path.join(source, 'libyuv/LICENSE'), path.join(vendor, 'COPYING.libyuv'));
fs.copyFileSync(path.join(source, 'soundtouch/LICENSE'), path.join(vendor, 'COPYING.soundtouch'));
fs.copyFileSync(path.join(source, 'dav1d/COPYING'), path.join(vendor, 'COPYING.dav1d'));
fs.copyFileSync(path.join(ndk, 'NOTICE.toolchain'), path.join(vendor, 'NOTICE.NDK.toolchain'));
fs.copyFileSync(path.join(ndk, 'sources/android/cpufeatures/NOTICE'), path.join(vendor, 'NOTICE.cpufeatures'));
console.log(`Produced ${selected.join(', ')} full decoder IJKPlayer native AARs; metadata: ${prior}`);
