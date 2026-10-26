/* Playback timing helpers; seconds are media time unless explicitly marked wall time. */
#ifndef VIBE_PLAYBACK_TIMING_H
#define VIBE_PLAYBACK_TIMING_H
#include <math.h>

static inline double vibe_playback_rate(double rate)
{
    return isfinite(rate) && rate >= 0.1 && rate <= 4.0 ? rate : 1.0;
}

/* Once started, the processor owns audio on this seek timeline, even back at 1x. */
static inline int vibe_soundtouch_should_process(int active, double rate)
{
    return active || vibe_playback_rate(rate) != 1.0;
}

static inline double vibe_audio_clock_pts(double end_pts, int pending_bytes, int bytes_per_second,
                                         double driver_wall_seconds, double output_rate,
                                         double processor_media_seconds)
{
    return end_pts - processor_media_seconds -
        (pending_bytes / (double)bytes_per_second + driver_wall_seconds) * output_rate;
}

/* MediaCodec operating-rate is output frames per wall second; source PTS do not change. */
static inline double vibe_codec_operating_rate(double fps, double rate)
{
    return isfinite(fps) && fps > 0 && fps <= 240 ? fps * vibe_playback_rate(rate) : 0;
}

/* Keep a wall-clock presentation grid while source PTS and decoding remain intact. */
static inline int vibe_video_present_due(double now, double hz, double *next)
{
    if (!isfinite(hz) || hz < 1 || hz > 1000) hz = 60;
    if (now + 0.000001 < *next) return 0;
    double following = *next + 1.0 / hz;
    *next = following > now ? following : now + 1.0 / hz;
    return 1;
}


#ifdef __cplusplus
extern "C" {
#endif
void vibe_soundtouch_clear(void *handle);
int vibe_soundtouch_translate(void *handle, const unsigned char *input, int input_bytes,
                             short *output, int capacity_bytes, float rate, int channels, int sample_rate);
double vibe_soundtouch_pending_seconds(void *handle, int sample_rate, float rate);
#ifdef __cplusplus
}
#endif
#endif
