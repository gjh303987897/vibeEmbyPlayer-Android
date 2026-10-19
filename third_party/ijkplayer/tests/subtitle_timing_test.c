/* Exercises the actual patched subtitle_refresh function with deterministic queues/clocks.
 * SDL, JNI notifications and drawing are mocked; this is not an Android rendering test. */
#include <assert.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

typedef struct { const char *text, *ass; } Rect;
typedef struct { int format, num_rects; unsigned start_display_time, end_display_time; Rect **rects; } Sub;
typedef struct { Sub sub; double pts; int serial, uploaded; } Frame;
typedef struct { Frame frames[2]; int first; } Queue;
typedef struct { Queue subpq; struct { int serial; } subtitleq; int seek_req, abort_request; double clock; } VideoState;
typedef struct { VideoState *is; void *subtitle_mutex; int subtitle_active, subtitle_seek_pending; int64_t subtitle_delay_ms, subtitle_render_delay_ms; } FFPlayer;
static char visible[128];
static int bitmap_visible, locks;
static void SDL_LockMutex(void *m) { locks++; }
static void SDL_UnlockMutex(void *m) { locks--; }
static double get_master_clock(VideoState *s) { return s->clock; }
static int frame_queue_nb_remaining(Queue *q) { return 2 - q->first; }
static Frame *frame_queue_peek(Queue *q) { return &q->frames[q->first]; }
static Frame *frame_queue_peek_next(Queue *q) { return &q->frames[q->first + 1]; }
static void frame_queue_next(Queue *q) { q->first++; }
static void vibe_bitmap_subtitles_clear_l(FFPlayer *p, int notify) {
    bitmap_visible = 0;
    if (notify) visible[0] = 0;
}
static void vibe_bitmap_subtitles_present_l(FFPlayer *p, Frame *frame) { bitmap_visible = 1; }
static size_t av_strlcpy(char *out, const char *in, size_t n) {
    snprintf(out, n, "%s", in);
    return strlen(in);
}
static void parse_ass_subtitle(const char *ass, char *out) { av_strlcpy(out, ass, 128); }
#define FFP_MSG_TIMED_TEXT 99
static void ffp_notify_msg4(FFPlayer *p, int what, int a, int b, const char *s, size_t n) {
    assert(what == FFP_MSG_TIMED_TEXT);
    av_strlcpy(visible, s, sizeof(visible));
}

#include "subtitle_refresh.inc"

static Rect text = { "caption", NULL };
static Rect *rects[] = { &text };
static VideoState state;
static FFPlayer player;
static void reset(int format, int64_t delay) {
    memset(&state, 0, sizeof(state));
    memset(&player, 0, sizeof(player));
    state.subtitleq.serial = 1;
    for (int i = 0; i < 2; i++) {
        state.subpq.frames[i].pts = i == 0 ? 2.0 : 6.0;
        state.subpq.frames[i].serial = 1;
        state.subpq.frames[i].sub = (Sub){format, 1, 0, 2000, rects};
    }
    player.is = &state;
    player.subtitle_active = 1;
    player.subtitle_delay_ms = delay;
    visible[0] = 0;
    bitmap_visible = locks = 0;
}
static void at(double time, int expected, int format) {
    state.clock = time;
    subtitle_refresh(&player);
    assert(state.clock == time); /* Video/audio clock must not be shifted. */
    assert(locks == 0);
    assert((format ? visible[0] != 0 : bitmap_visible) == expected);
}
int main(void) {
    for (int format = 0; format <= 1; format++) {
        reset(format, 2000);
        at(3.999, 0, format);
        at(4.0, 1, format);
        at(6.001, 0, format);
        at(8.0, 1, format);
        at(10.001, 0, format);
        reset(format, -2000);
        at(0, 1, format);
        at(2.001, 0, format);
        at(4.0, 1, format);
        at(6.001, 0, format);
        reset(format, 0);
        at(1.999, 0, format);
        at(2.0, 1, format);
        at(4.001, 0, format);
        reset(format, 0);
        at(3, 1, format);
        player.subtitle_delay_ms = 2000;
        at(3, 0, format); /* Re-evaluate a displayed cue without requiring a live stream seek. */
        at(4, 1, format);
        player.subtitle_delay_ms = 0;
        at(4, 1, format); /* Reset must republish the current cue after the clear. */
        /* The JNI setter marks every edit dirty, including rapid edits back to zero. */
        visible[0] = 0;
        bitmap_visible = 0;
        player.subtitle_render_delay_ms = INT64_MIN;
        at(4, 1, format);
        at(4.001, 0, format);
        reset(format, -2000);
        player.subtitle_seek_pending = 1;
        at(0, 0, format);
        player.subtitle_seek_pending = 0;
        at(0, 1, format);
        reset(format, 0);
        state.subpq.frames[0].serial = 0;
        at(3, 0, format);
        assert(state.subpq.first == 1);
        at(6, 1, format);
        reset(format, 0);
        state.clock = NAN;
        subtitle_refresh(&player);
        assert(state.subpq.first == 0 && locks == 0);
    }
    puts("PASS: actual subtitle_refresh text/bitmap queues, positive/negative/zero offsets, expiration, seek gating, serial isolation and unchanged A/V clocks");
    return 0;
}
