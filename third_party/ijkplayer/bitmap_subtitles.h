#ifndef VIBE_BITMAP_SUBTITLES_H
#define VIBE_BITMAP_SUBTITLES_H

#include <jni.h>
struct FFPlayer;
struct Frame;

/* Caller holds subtitle_mutex for these snapshot mutations. The snapshot owns
 * every index/palette byte; it never borrows AVSubtitle storage. */
void vibe_bitmap_subtitles_clear_l(struct FFPlayer *ffp, int notify);
int vibe_bitmap_subtitles_present_l(struct FFPlayer *ffp, const struct Frame *frame);
void vibe_bitmap_subtitles_seek_pending(struct FFPlayer *ffp, int pending);
jobject vibe_bitmap_subtitles_read(JNIEnv *env, struct FFPlayer *ffp, jlong revision);

#endif
