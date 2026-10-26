/* PCM presentation-to-media mapping. LGPL-2.1-or-later, like the IJK integration. */
#ifndef VIBE_AUDIO_PRESENTATION_CLOCK_H
#define VIBE_AUDIO_PRESENTATION_CLOCK_H
#include <math.h>
#include <stdint.h>
#include <string.h>

/* Over five seconds of stereo S16 48kHz callbacks, including tempo boundaries. */
#define VIBE_AUDIO_POINTS 4096
typedef struct VibeAudioPoint {
    uint64_t first_frame, end_frame;
    double end_pts, media_rate;
} VibeAudioPoint;
typedef struct VibeAudioTimeline {
    VibeAudioPoint points[VIBE_AUDIO_POINTS];
    int first, count, serial;
} VibeAudioTimeline;

static inline void vibe_audio_timeline_clear(VibeAudioTimeline *t)
{
    t->first = t->count = 0;
    t->serial = -1;
}

static inline void vibe_audio_timeline_add(VibeAudioTimeline *t, uint64_t first,
    uint64_t end, double pts, double rate, int serial)
{
    if (end <= first || !isfinite(pts) || !isfinite(rate) || rate < 0 || rate > 4) return;
    if (serial != t->serial) {
        vibe_audio_timeline_clear(t);
        t->serial = serial;
    }
    if (t->count == VIBE_AUDIO_POINTS) {
        t->first = (t->first + 1) % VIBE_AUDIO_POINTS;
        --t->count;
    }
    VibeAudioPoint *p = &t->points[(t->first + t->count++) % VIBE_AUDIO_POINTS];
    p->first_frame = first;
    p->end_frame = end;
    p->end_pts = pts;
    p->media_rate = rate;
}

/* Frames are AudioTrack output frames, not source frames or frames at the newly selected tempo. */
static inline int vibe_audio_timeline_at(const VibeAudioTimeline *t, double frame,
    double sample_rate, int serial, double *pts, double *rate)
{
    if (!t->count || t->serial != serial || !isfinite(frame) || sample_rate <= 0) return 0;
    const VibeAudioPoint *first = &t->points[t->first];
    const VibeAudioPoint *last = &t->points[(t->first + t->count - 1) % VIBE_AUDIO_POINTS];
    if ((frame < first->first_frame && first->first_frame != 0) || frame > last->end_frame) return 0;
    int lo = 0, hi = t->count - 1;
    while (lo < hi) {
        int mid = lo + (hi - lo) / 2;
        if (t->points[(t->first + mid) % VIBE_AUDIO_POINTS].end_frame <= frame) lo = mid + 1;
        else hi = mid;
    }
    const VibeAudioPoint *p = &t->points[(t->first + lo) % VIBE_AUDIO_POINTS];
    if (frame < p->first_frame && p->first_frame != 0) return 0;
    *pts = p->end_pts - ((double)p->end_frame - frame) * p->media_rate / sample_rate;
    *rate = p->media_rate;
    return 1;
}

/* Timestamp and now are both CLOCK_MONOTONIC nanoseconds. A future timestamp retains preroll. */
static inline double vibe_audio_presented_frame(int64_t position, int64_t timestamp_ns,
    int64_t now_ns, double sample_rate, double hardware_rate, uint64_t written)
{
    double frame = (double)position + (now_ns - timestamp_ns) / 1e9 * sample_rate * hardware_rate;
    return fmin(frame, (double)written);
}
#endif
