/* LGPL-2.1-or-later; adapter for the pinned SoundTouch library (COPYING.soundtouch). */
#include "SoundTouch.h"
#include "playback_timing.h"
#include <new>

extern "C" void vibe_soundtouch_clear(void *handle)
{
    if (!handle) return;
    auto *processor = static_cast<soundtouch::SoundTouch *>(handle);
    // This pinned version's clear() leaves samplesOutput from the preceding timeline.
    // Reconstruct in place so the upstream handle owner still destroys the same allocation.
    processor->~SoundTouch();
    new (processor) soundtouch::SoundTouch();
}

extern "C" int vibe_soundtouch_translate(void *handle, const unsigned char *input, int input_bytes,
                                        short *output, int capacity_bytes, float rate, int channels, int sample_rate)
{
    if (!handle || !output || channels <= 0 || sample_rate <= 0 || input_bytes < 0 || capacity_bytes < 0)
        return 0;
    auto *processor = static_cast<soundtouch::SoundTouch *>(handle);
    processor->setSampleRate(sample_rate);
    processor->setChannels(channels);
    processor->setRate(1.0f);
    processor->setPitch(1.0f);
    // Tempo changes preserve buffered input/output; only a seek starts a new timeline.
    processor->setTempo(vibe_playback_rate(rate));
    if (input && input_bytes > 0)
        processor->putSamples(reinterpret_cast<const soundtouch::SAMPLETYPE *>(input), input_bytes / (channels * 2));
    // A single bounded receive preserves every sample and never overwrites the caller's buffer.
    return processor->receiveSamples(output, capacity_bytes / (channels * 2)) * channels * 2;
}

extern "C" double vibe_soundtouch_pending_seconds(void *handle, int sample_rate, float rate)
{
    if (!handle || sample_rate <= 0) return 0.0;
    auto *processor = static_cast<soundtouch::SoundTouch *>(handle);
    return (processor->numUnprocessedSamples() + processor->numSamples() * vibe_playback_rate(rate)) / sample_rate;
}
