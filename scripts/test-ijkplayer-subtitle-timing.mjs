#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(root, 'build/ijk-native/source/ijkplayer/ijkmedia/ijkplayer/ff_ffplay.c');
if (!fs.existsSync(source)) throw Error('Run build-ijkplayer-native.mjs first to prepare pinned/patched native sources.');
const text = fs.readFileSync(source, 'utf8');
const start = text.indexOf('static void subtitle_refresh(FFPlayer *ffp)');
const end = text.indexOf('\n}', start) + 2;
if (start < 0 || end <= start) throw Error('Cannot locate native subtitle_refresh.');
const build = path.join(root, 'build/subtitle-enhancements/host-test');
fs.mkdirSync(build, { recursive: true });
const include = path.join(build, 'subtitle_refresh.inc');
const native = text.slice(start, end);
fs.writeFileSync(include, native);
const executable = path.join(build, process.platform === 'win32' ? 'subtitle-timing.exe' : 'subtitle-timing');
const baselineExecutable = path.join(build, process.platform === 'win32' ? 'subtitle-timing-baseline.exe' : 'subtitle-timing-baseline');
const run = (program, args) => {
  const result = spawnSync(program, args, { cwd: root, stdio: 'inherit', shell: false });
  if (result.error || result.status !== 0) throw result.error || Error(`${program} exited ${result.status}`);
};
run(process.env.CC || 'clang', ['-std=c11', '-Wall', '-Wextra', '-Werror', '-Wno-unused-parameter',
  `-I${build}`, path.join(root, 'third_party/ijkplayer/tests/subtitle_timing_test.c'), '-o', executable]);
run(executable, []);
// The pre-feature clock must fail the same assertions, proving the checks detect a missing shift.
if (!native.includes('clock -= ffp->subtitle_delay_ms / 1000.0;')) throw Error('Missing timing overlay.');
fs.writeFileSync(include, native.replace('clock -= ffp->subtitle_delay_ms / 1000.0;', '/* baseline: no subtitle offset */'));
try {
  run(process.env.CC || 'clang', ['-std=c11', '-Wno-unused-parameter', `-I${build}`,
    path.join(root, 'third_party/ijkplayer/tests/subtitle_timing_test.c'), '-o', baselineExecutable]);
  const old = spawnSync(baselineExecutable, [], { cwd: root, stdio: 'pipe', shell: false });
  if (old.error || old.status === 0) throw old.error || Error('Baseline unexpectedly passed.');
  console.log('PASS: the old unshifted clock fails the same native queue regression.');
} finally { fs.writeFileSync(include, native); }
