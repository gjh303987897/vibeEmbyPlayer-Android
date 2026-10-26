#!/usr/bin/env node
// Deterministic output-frame/media mapping, with no audio device or credentials.
import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const build = path.join(root, 'build/audio-presentation-host');
fs.mkdirSync(build, {recursive: true});
const output = path.join(build, process.platform === 'win32' ? 'audio-presentation.exe' : 'audio-presentation');
for (const [program, args] of [
  [process.env.CC || 'clang', ['-std=c99', '-Wall', '-Wextra', '-Werror', '-Ithird_party/ijkplayer',
    'third_party/ijkplayer/tests/audio_presentation_clock_test.c', '-o', output,
    ...(process.platform === 'win32' ? [] : ['-lm'])]],
  [output, []],
]) {
  const result = spawnSync(program, args, {cwd: root, stdio: 'inherit', shell: false});
  if (result.error || result.status !== 0) throw result.error || Error(`${program} exited ${result.status}`);
}
