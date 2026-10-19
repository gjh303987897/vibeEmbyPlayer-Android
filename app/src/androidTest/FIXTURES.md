# Generated video fixtures

`res/raw/video_sample_high10.mp4` contains 15 seconds of synthetic SMPTE colour bars
and a 440 Hz sine wave. It contains no user media. Reproduce it with FFmpeg:

```powershell
ffmpeg -f lavfi -i 'smptebars=size=320x180:rate=24' -f lavfi -i 'sine=frequency=440:sample_rate=48000' -t 15 -c:v libx264 -profile:v high10 -pix_fmt yuv420p10le -preset veryfast -crf 28 -g 24 -c:a aac -b:a 32k -movflags +faststart video_sample_high10.mp4
```

The pinned IJK MediaCodec pipeline explicitly rejects H.264 High 10, so this
fixture uses AVCodec even when the hardware preference remains selected. The
`automaticSoftwareFallbackThenHardwareReplacesSurfaceAcrossSourceChanges` test
then plays the normal 8-bit H.264 fixture in the same `IjkPlayerView`, covering
both repeated `beginLoading()` calls and direct `play()` source changes. It checks
actual decoder selection and colour-bar pixels, as well as Surface replacement.
The test requires a device with an AVC MediaCodec; its initial baseline skips the
test when no such decoder is usable.

`res/raw/video_sample_av1.mp4` contains fifteen seconds of 160×96 synthetic colour
bars at 24 fps, AAC sine-wave audio and an embedded `mov_text` cue reading
`AV1 fixture subtitle`. It uses AV1 Main with 8-bit YUV 4:2:0, so the regression
checks codec availability without requiring a device to decode 4K in real time.
Generate an SRT with that caption from 00:00:00,000 to 00:00:15,000, then run:

```powershell
ffmpeg -f lavfi -i 'smptebars=size=160x96:rate=24' -f lavfi -i 'sine=frequency=440:sample_rate=48000' -i subtitles.srt -t 15 -map 0:v -map 1:a -map 2:s -c:v libaom-av1 -cpu-used 8 -row-mt 1 -threads 2 -crf 40 -b:v 0 -pix_fmt yuv420p -g 24 -c:a aac -b:a 32k -c:s mov_text -metadata:s:s:0 language=eng -disposition:s:0 default -movflags +faststart video_sample_av1.mp4
```

`av1RendersWithAudioAndSubtitlesInBothDecoderModesAndThenPlaysH264` verifies a
real video decoder, AAC track/decoder, advancing progress, embedded caption and
colour-bar pixels with both hardware and software preferences. It then switches
to normal H.264 in the same view, including Surface replacement when AV1 used
software fallback under the hardware preference. It restores the original
decoder setting afterward. The fixture contains no user media.
