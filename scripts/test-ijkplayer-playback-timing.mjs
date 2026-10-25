#!/usr/bin/env node
// Host-only regression: no ADB/emulator. Uses production IJK functions and pinned SoundTouch.
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = fs.readFileSync(path.join(root, 'build/ijk-native/source/ijkplayer/ijkmedia/ijkplayer/ff_ffplay.c'), 'utf8');
const build = path.join(root, 'build/speed-playback/host');
const vendor = path.join(root, 'third_party/ijkplayer');
const soundtouch = path.join(root, 'build/ijk-native/source/soundtouch');
fs.mkdirSync(build, { recursive: true });
const extract = name => {
  const start = source.indexOf(`static ${name}`);
  const end = source.indexOf('\n}', start) + 2;
  if (start < 0 || end <= start) throw Error(`Missing native function ${name}`);
  return source.slice(start, end);
};
fs.writeFileSync(path.join(build, 'native_clock.inc'), ['double get_clock(', 'void set_clock_at(', 'void set_clock(', 'void set_clock_speed('].map(extract).join('\n'));
const rateStart = source.indexOf('void ffp_set_playback_rate(');
fs.writeFileSync(path.join(build, 'native_rate.inc'), source.slice(rateStart, source.indexOf('\n}', rateStart) + 2));
const delay = extract('double compute_target_delay(');
const delayFile = path.join(build, 'native_delay.inc');
fs.writeFileSync(delayFile, delay);
// Check the integration points not covered by the small host scheduler harness.
for (const expected of ['duration = vp_duration(is, vp, nextvp) / rate;', 'set_clock_speed(&is->vidclk, rate);',
  'set_clock_speed(&is->extclk, rate);', '*remaining_time = FFMIN(*remaining_time, delay);',
  'vibe_audio_clock_pts(is->audio_clock',
  'if (ffp->soundtouch_enable && is->soundtouch_serial != af->serial)',
  'is->soundtouch_active = vibe_soundtouch_should_process(is->soundtouch_active, playback_rate);',
  'ffp->soundtouch_enable && is->soundtouch_active && !is->abort_request',
  'is->soundtouch_delay = ffp->soundtouch_enable && is->soundtouch_active']) {
  if (!source.includes(expected)) throw Error(`Missing integration: ${expected}`);
}
if (source.includes('soundtouch_rate')) throw Error('Rate changes must not clear the SoundTouch timeline');
const names = fs.readFileSync(path.join(soundtouch, 'Android.mk'), 'utf8')
  .split('\n').filter(line => line.startsWith('LOCAL_SRC_FILES += source/')).map(line => line.split('+=')[1].trim());
const flags = ['-std=c++17', '-O2', '-DSOUNDTOUCH_DISABLE_X86_OPTIMIZATIONS', '-DANDROID',
  `-I${build}`, `-I${vendor}`, `-I${soundtouch}/include`, `-I${soundtouch}/source/SoundTouch`,
  path.join(vendor, 'tests/playback_timing_test.cpp'), path.join(vendor, 'playback_soundtouch.cpp'),
  ...names.map(name => path.join(soundtouch, name))];
const executable = path.join(build, process.platform === 'win32' ? 'playback-timing.exe' : 'playback-timing');
const baselineExecutable = path.join(build, process.platform === 'win32' ? 'playback-timing-baseline.exe' : 'playback-timing-baseline');
const run = (program, args) => {
  const result = spawnSync(program, args, { cwd: root, stdio: 'inherit', shell: false });
  if (result.error || result.status !== 0) throw result.error || Error(`${program} exited ${result.status}`);
};
run(process.env.CXX || 'clang++', [...flags, '-o', executable]);
run(executable, []);
// The pre-fix scheduler must fail the same cadence assertions.
fs.writeFileSync(delayFile, delay.replace('delay /= rate;', '/* baseline: unscaled video delay */'));
try {
  run(process.env.CXX || 'clang++', [...flags, '-o', baselineExecutable]);
  const baseline = spawnSync(baselineExecutable, [], { cwd: root, stdio: 'pipe', shell: false });
  if (baseline.error || baseline.status === 0) throw baseline.error || Error('Unscaled scheduler unexpectedly passed');
  console.log('PASS: pre-fix video cadence fails the same host regression');
} finally { fs.writeFileSync(delayFile, delay); }
