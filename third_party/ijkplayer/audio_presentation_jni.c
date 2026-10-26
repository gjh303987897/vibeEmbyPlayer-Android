/* AudioTrack presentation timestamps. SPDX-License-Identifier: LGPL-2.1-or-later */
#include "audio_presentation_jni.h"
#include "audio_presentation_clock.h"
#include <stdlib.h>
#include <time.h>

struct VibeAudioPresentation {
    jobject track; /* Borrowed from SDL_Android_AudioTrack; destroyed after this object. */
    jobject timestamp;
    jmethodID get_timestamp, get_route, route_id;
    jfieldID timestamp_frame, timestamp_ns;
    VibeAudioTimeline timeline;
    uint64_t written, offered;
    int sample_rate, frame_bytes, playing, valid, seen, stable, device_id;
    int64_t epoch_ns, next_poll_ns, next_route_ns, position, time_ns;
};

static int64_t monotonic_ns(void)
{
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (int64_t)now.tv_sec * 1000000000LL + now.tv_nsec;
}

static int clear_exception(JNIEnv *env)
{
    if (!(*env)->ExceptionCheck(env)) return 0;
    (*env)->ExceptionClear(env);
    return 1;
}

VibeAudioPresentation *vibe_audio_presentation_new(JNIEnv *env, jobject track, int sample_rate, int frame_bytes)
{
    VibeAudioPresentation *p = calloc(1, sizeof(*p));
    if (!p) return NULL;
    p->track = track;
    p->sample_rate = sample_rate;
    p->frame_bytes = frame_bytes;
    p->device_id = -1;
    p->epoch_ns = monotonic_ns();
    vibe_audio_timeline_clear(&p->timeline);
    jclass cls = (*env)->GetObjectClass(env, track);
    if (cls) {
        p->get_timestamp = (*env)->GetMethodID(env, cls, "getTimestamp", "(Landroid/media/AudioTimestamp;)Z");
        clear_exception(env);
        p->get_route = (*env)->GetMethodID(env, cls, "getRoutedDevice", "()Landroid/media/AudioDeviceInfo;");
        clear_exception(env);
        (*env)->DeleteLocalRef(env, cls);
    }
    clear_exception(env);
    cls = (*env)->FindClass(env, "android/media/AudioDeviceInfo");
    if (cls) {
        p->route_id = (*env)->GetMethodID(env, cls, "getId", "()I");
        (*env)->DeleteLocalRef(env, cls);
    }
    clear_exception(env);
    cls = (*env)->FindClass(env, "android/media/AudioTimestamp");
    if (cls) {
        jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "()V");
        p->timestamp_frame = (*env)->GetFieldID(env, cls, "framePosition", "J");
        p->timestamp_ns = (*env)->GetFieldID(env, cls, "nanoTime", "J");
        if (!clear_exception(env) && ctor) {
            jobject local = (*env)->NewObject(env, cls, ctor);
            if (local) {
                p->timestamp = (*env)->NewGlobalRef(env, local);
                (*env)->DeleteLocalRef(env, local);
            }
        }
        (*env)->DeleteLocalRef(env, cls);
    }
    clear_exception(env);
    return p; /* Unsupported timestamps retain the original player's fallback clock. */
}

void vibe_audio_presentation_free(JNIEnv *env, VibeAudioPresentation *p)
{
    if (!p) return;
    if (p->timestamp) (*env)->DeleteGlobalRef(env, p->timestamp);
    free(p);
}

void vibe_audio_presentation_flush(VibeAudioPresentation *p)
{
    if (!p) return;
    p->written = p->offered = 0;
    p->valid = p->seen = p->stable = 0;
    p->next_poll_ns = 0;
    p->epoch_ns = monotonic_ns();
    vibe_audio_timeline_clear(&p->timeline);
}

void vibe_audio_presentation_pause(VibeAudioPresentation *p, int paused)
{
    if (!p) return;
    p->playing = !paused;
    p->valid = p->seen = p->stable = 0;
    p->next_poll_ns = 0;
    p->epoch_ns = monotonic_ns();
}

void vibe_audio_presentation_begin(VibeAudioPresentation *p)
{
    if (p) p->offered = 0;
}

void vibe_audio_presentation_record(VibeAudioPresentation *p, int bytes, double pts, double rate, int serial)
{
    if (!p || p->frame_bytes <= 0 || bytes <= 0) return;
    uint64_t first = p->written + p->offered;
    p->offered += bytes / p->frame_bytes;
    vibe_audio_timeline_add(&p->timeline, first, p->written + p->offered, pts, rate, serial);
}

void vibe_audio_presentation_written(VibeAudioPresentation *p, int bytes)
{
    if (p && p->frame_bytes > 0 && bytes > 0) p->written += bytes / p->frame_bytes;
}

int vibe_audio_presentation_get(JNIEnv *env, VibeAudioPresentation *p, int serial, double *pts, double *rate)
{
    if (!p || !p->playing || !p->timestamp || !p->get_timestamp || p->sample_rate <= 0) return 0;
    int64_t now = monotonic_ns();
    if (p->get_route && p->route_id && now >= p->next_route_ns) {
        jobject device = (*env)->CallObjectMethod(env, p->track, p->get_route);
        if (!clear_exception(env) && device) {
            int id = (*env)->CallIntMethod(env, device, p->route_id);
            if (!clear_exception(env) && id != p->device_id) {
                if (p->device_id >= 0) {
                    p->valid = p->seen = p->stable = 0;
                    p->epoch_ns = now;
                    p->next_poll_ns = 0;
                }
                p->device_id = id;
            }
        }
        if (device) (*env)->DeleteLocalRef(env, device);
        p->next_route_ns = now + 250000000LL;
    }
    if (now >= p->next_poll_ns) {
        jboolean ok = (*env)->CallBooleanMethod(env, p->track, p->get_timestamp, p->timestamp);
        if (clear_exception(env)) ok = JNI_FALSE;
        if (ok) {
            int64_t position = (*env)->GetLongField(env, p->timestamp, p->timestamp_frame);
            int64_t timestamp = (*env)->GetLongField(env, p->timestamp, p->timestamp_ns);
            if (position >= 0 && (uint64_t)position <= p->written && timestamp >= p->epoch_ns &&
                timestamp <= now + 5000000000LL && timestamp >= now - 5000000000LL) {
                if (p->seen && (position < p->position || timestamp < p->time_ns)) p->stable = 0;
                if (p->seen && position > p->position && timestamp > p->time_ns) ++p->stable;
                p->position = position;
                p->time_ns = timestamp;
                p->seen = 1;
                p->valid = p->stable > 0;
            } else p->valid = p->seen = p->stable = 0;
        } else p->valid = p->seen = p->stable = 0;
        /* Follow Android's recommendation: fast warmup, infrequent stable polling. */
        p->next_poll_ns = now + (p->stable >= 3 ? 10000000000LL : 20000000LL);
    }
    if (!p->valid) return 0;
    double frame = vibe_audio_presented_frame(p->position, p->time_ns, now, p->sample_rate, 1, p->written);
    if (frame >= p->written) p->next_poll_ns = now; /* Reacquire after an underrun, not ten seconds later. */
    return vibe_audio_timeline_at(&p->timeline, frame, p->sample_rate, serial, pts, rate);
}
