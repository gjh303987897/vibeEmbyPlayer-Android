/* SPDX-License-Identifier: LGPL-2.1-or-later */
#ifndef VIBE_AUDIO_PRESENTATION_JNI_H
#define VIBE_AUDIO_PRESENTATION_JNI_H
#include <jni.h>
typedef struct VibeAudioPresentation VibeAudioPresentation;
VibeAudioPresentation *vibe_audio_presentation_new(JNIEnv *, jobject track, int sample_rate, int frame_bytes);
void vibe_audio_presentation_free(JNIEnv *, VibeAudioPresentation *);
void vibe_audio_presentation_flush(VibeAudioPresentation *);
void vibe_audio_presentation_pause(VibeAudioPresentation *, int paused);
void vibe_audio_presentation_begin(VibeAudioPresentation *);
void vibe_audio_presentation_record(VibeAudioPresentation *, int bytes, double end_pts, double rate, int serial);
void vibe_audio_presentation_written(VibeAudioPresentation *, int bytes);
int vibe_audio_presentation_get(JNIEnv *, VibeAudioPresentation *, int serial, double *pts, double *rate);
#endif
