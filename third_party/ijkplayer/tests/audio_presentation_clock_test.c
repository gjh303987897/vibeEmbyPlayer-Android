#include <assert.h>
#include <math.h>
#include <stdio.h>
#include "audio_presentation_clock.h"
#include "playback_timing.h"

static VibeAudioTimeline timeline;
static void close_to(double a, double b) { assert(fabs(a - b) < 0.000001); }

int main(void)
{
    double pts, rate;
    const double sr = 48000;
    /* Speaker and Bluetooth delays, including queues holding media from two different tempos. */
    for (int delay_ms = 40; delay_ms <= 1000; delay_ms += 40) {
        vibe_audio_timeline_clear(&timeline);
        uint64_t written = 0;
        double media = 10;
        for (int block = 0; block < 1200; block++) {
            double tempo = block < 400 ? 1 : block < 800 ? 2 : 1;
            media += .001 * tempo;
            vibe_audio_timeline_add(&timeline, written, written + 48, media, tempo, 7);
            written += 48;
            if (block < delay_ms) continue;
            double presented = written - delay_ms * 48.0;
            assert(vibe_audio_timeline_at(&timeline, presented, sr, 7, &pts, &rate));
            double seconds = presented / sr;
            double expected = 10 + (seconds <= .4 ? seconds : seconds <= .8 ? .4 + (seconds - .4) * 2 : 1.2 + seconds - .8);
            close_to(pts, expected);
        }
    }
    puts("PASS: actual queued PCM tempo maps speaker/Bluetooth presentation through 1x-2x-1x");

    /* The former clock applies the newest tempo to audio still queued at the old tempo. */
    vibe_audio_timeline_clear(&timeline);
    vibe_audio_timeline_add(&timeline, 0, 48000, 11, 1, 1);
    vibe_audio_timeline_add(&timeline, 48000, 52800, 11.2, 2, 1);
    assert(vibe_audio_timeline_at(&timeline, .8 * sr, sr, 1, &pts, &rate));
    close_to(pts, 10.8); close_to(rate, 1);
    assert(fabs(vibe_audio_clock_pts(11.2, 0, 192000, .3, 2, 0) - pts) > .15);
    assert(vibe_audio_timeline_at(&timeline, sr, sr, 1, &pts, &rate));
    close_to(pts, 11); close_to(rate, 2);

    /* A seek cannot select points from another serial, including before the first new sample. */
    assert(!vibe_audio_timeline_at(&timeline, 12000, sr, 2, &pts, &rate));
    vibe_audio_timeline_add(&timeline, 52800, 52848, 90.001, 1, 2);
    assert(!vibe_audio_timeline_at(&timeline, 52000, sr, 2, &pts, &rate));
    vibe_audio_timeline_clear(&timeline);
    assert(!vibe_audio_timeline_at(&timeline, 0, sr, 2, &pts, &rate));
    vibe_audio_timeline_add(&timeline, 0, 48, 90.001, 1, 2);
    assert(vibe_audio_timeline_at(&timeline, 0, sr, 2, &pts, &rate)); close_to(pts, 90);

    /* Future presentation, drained/underrun output, long running counters and ring eviction. */
    close_to(vibe_audio_presented_frame(4800, 2000000000, 2100000000, sr, 1, 20000), 9600);
    close_to(vibe_audio_presented_frame(0, 2500000000, 2000000000, sr, 1, 20000), -24000);
    close_to(vibe_audio_presented_frame(4800, 1000000000, 9000000000, sr, 1, 9600), 9600);
    vibe_audio_timeline_clear(&timeline);
    uint64_t origin = (uint64_t)1 << 33;
    for (int i = 0; i < VIBE_AUDIO_POINTS + 100; ++i)
        vibe_audio_timeline_add(&timeline, origin + i * 48, origin + (i + 1) * 48, 100 + (i + 1) * .002, 2, 3);
    assert(!vibe_audio_timeline_at(&timeline, origin, sr, 3, &pts, &rate));
    assert(vibe_audio_timeline_at(&timeline, origin + 200 * 48, sr, 3, &pts, &rate)); close_to(pts, 100.4);
    puts("PASS: timestamp extrapolation, seek isolation, preroll, starvation, 64-bit counters and bounded history");
    close_to(vibe_codec_operating_rate(60, 2), 120);
    close_to(vibe_codec_operating_rate(60, 1), 60);
    close_to(vibe_codec_operating_rate(24000.0 / 1001, .5), 12000.0 / 1001);
    close_to(vibe_codec_operating_rate(NAN, 2), 0);
    close_to(vibe_codec_operating_rate(0, 2), 0);
    puts("PASS: hardware decoder rate follows source frame rate and selected speed without changing media PTS");
    for (int hz = 30; hz <= 144; hz += 6) {
        double next = 0;
        int presented = 0;
        for (int i = 0; i < 24000; ++i)
            presented += vibe_video_present_due(i / 2400.0, hz, &next);
        assert(presented <= hz * 10 + 1);
        assert(presented >= hz * 10 - 1);
    }
    puts("PASS: 30-144Hz display presentation grid caps high-rate input while retaining full media timestamps");
}
