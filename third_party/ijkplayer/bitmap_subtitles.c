#include "bitmap_subtitles.h"
#include "bitmap_subtitle_pixels.h"
#include "ff_ffplay.h"
#include "ff_ffplay_def.h"
#include <android/bitmap.h>
#include <errno.h>
#include <string.h>

#define MAX_SUBTITLE_REGIONS 256
#define MAX_SUBTITLE_PIXELS (16U * 1024U * 1024U)

typedef struct BitmapRegion {
    int x, y, width, height, colors;
    uint32_t palette[256];
    uint8_t *indices;
} BitmapRegion;

struct FFPBitmapSubtitle {
    int width, height, count;
    BitmapRegion *regions;
};

static void free_snapshot(struct FFPBitmapSubtitle *snapshot)
{
    if (!snapshot)
        return;
    for (int i = 0; i < snapshot->count; ++i)
        av_free(snapshot->regions[i].indices);
    av_free(snapshot->regions);
    av_free(snapshot);
}

void vibe_bitmap_subtitles_clear_l(FFPlayer *ffp, int notify)
{
    free_snapshot(ffp->bitmap_subtitle);
    ffp->bitmap_subtitle = NULL;
    /* Keep revisions positive without signed overflow. */
    ffp->subtitle_revision = ffp->subtitle_revision == INT64_MAX ? 1 : ffp->subtitle_revision + 1;
    if (notify)
        ffp_notify_msg4(ffp, FFP_MSG_TIMED_TEXT, 0, 0, "", 1);
}

void vibe_bitmap_subtitles_seek_pending(FFPlayer *ffp, int pending)
{
    SDL_LockMutex(ffp->subtitle_mutex);
    ffp->subtitle_seek_pending = pending;
    if (pending)
        vibe_bitmap_subtitles_clear_l(ffp, 1);
    SDL_UnlockMutex(ffp->subtitle_mutex);
}

int vibe_bitmap_subtitles_present_l(FFPlayer *ffp, const Frame *frame)
{
    const AVSubtitle *sub = &frame->sub;
    struct FFPBitmapSubtitle *snapshot = NULL;
    size_t total_pixels = 0;
    int ret = AVERROR_INVALIDDATA;
    int width = frame->width;
    int height = frame->height;
    if (!sub->num_rects) {
        vibe_bitmap_subtitles_clear_l(ffp, 1);
        return 0;
    }
    if (sub->num_rects > MAX_SUBTITLE_REGIONS || !sub->rects)
        goto fail;
    if (width <= 0 || height <= 0) {
        if (ffp->is->video_st) {
            width = ffp->is->video_st->codecpar->width;
            height = ffp->is->video_st->codecpar->height;
        }
        /* Some DVD streams do not advertise a canvas. Use the video canvas
         * when available; otherwise derive the coordinate space from extents. */
        if (width <= 0 || height <= 0) {
            width = height = 0;
            for (unsigned i = 0; i < sub->num_rects; ++i) {
                const AVSubtitleRect *rect = sub->rects[i];
                if (!rect || rect->x < 0 || rect->y < 0 || rect->w <= 0 || rect->h <= 0 ||
                    rect->x > INT_MAX - rect->w || rect->y > INT_MAX - rect->h)
                    goto fail;
                width = FFMAX(width, rect->x + rect->w);
                height = FFMAX(height, rect->y + rect->h);
            }
        }
    }
    if (av_image_check_size(width, height, 0, ffp) < 0)
        goto fail;
    snapshot = av_mallocz(sizeof(*snapshot));
    if (!snapshot) {
        ret = AVERROR(ENOMEM);
        goto fail;
    }
    snapshot->width = width;
    snapshot->height = height;
    snapshot->regions = av_mallocz_array(sub->num_rects, sizeof(*snapshot->regions));
    if (!snapshot->regions) {
        ret = AVERROR(ENOMEM);
        goto fail;
    }
    snapshot->count = sub->num_rects;
    for (int i = 0; i < snapshot->count; ++i) {
        const AVSubtitleRect *rect = sub->rects[i];
        BitmapRegion *region = &snapshot->regions[i];
        size_t pixels;
        ptrdiff_t stride;
        if (!rect || rect->type != SUBTITLE_BITMAP || rect->x < 0 || rect->y < 0 ||
            rect->w <= 0 || rect->h <= 0 || rect->w > width || rect->h > height ||
            rect->x > width - rect->w || rect->y > height - rect->h ||
            rect->nb_colors <= 0 || rect->nb_colors > 256 || !rect->data[0] || !rect->data[1] ||
            av_image_check_size(rect->w, rect->h, 0, ffp) < 0)
            goto fail;
        stride = rect->linesize[0];
        if (stride == PTRDIFF_MIN || (stride < 0 ? -stride : stride) < rect->w ||
            (rect->h > 1 && (stride < 0 ? -stride : stride) > PTRDIFF_MAX / (rect->h - 1)) ||
            (size_t)rect->w > MAX_SUBTITLE_PIXELS / (size_t)rect->h)
            goto fail;
        pixels = (size_t)rect->w * rect->h;
        if (pixels > MAX_SUBTITLE_PIXELS - total_pixels)
            goto fail;
        total_pixels += pixels;
        region->x = rect->x;
        region->y = rect->y;
        region->width = rect->w;
        region->height = rect->h;
        region->colors = rect->nb_colors;
        memcpy(region->palette, rect->data[1], rect->nb_colors * sizeof(uint32_t));
        region->indices = av_malloc(pixels);
        if (!region->indices) {
            ret = AVERROR(ENOMEM);
            goto fail;
        }
        for (int y = 0; y < rect->h; ++y) {
            const uint8_t *input = rect->data[0] + (ptrdiff_t)y * stride;
            for (int x = 0; x < rect->w; ++x) {
                if (input[x] >= rect->nb_colors)
                    goto fail;
            }
            memcpy(region->indices + (size_t)y * rect->w, input, rect->w);
        }
    }
    vibe_bitmap_subtitles_clear_l(ffp, 0);
    ffp->bitmap_subtitle = snapshot;
    ffp_notify_msg4(ffp, FFP_MSG_TIMED_TEXT, 0, 0, "", 1);
    return 0;
fail:
    free_snapshot(snapshot);
    vibe_bitmap_subtitles_clear_l(ffp, 1);
    av_log(ffp, AV_LOG_ERROR, "Invalid or unallocatable bitmap subtitle: %d\n", ret);
    ffp_notify_msg2(ffp, FFP_MSG_ERROR, ret);
    return ret;
}

static void throw_bitmap_error(JNIEnv *env, const char *message)
{
    jclass exception;
    if ((*env)->ExceptionCheck(env))
        return;
    exception = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (exception) {
        (*env)->ThrowNew(env, exception, message);
        (*env)->DeleteLocalRef(env, exception);
    }
}

jobject vibe_bitmap_subtitles_read(JNIEnv *env, FFPlayer *ffp, jlong revision)
{
    jobject result = NULL;
    struct FFPBitmapSubtitle *snapshot;
    jclass region_class = NULL, frame_class = NULL, bitmap_class = NULL, config_class = NULL;
    jobject config = NULL;
    jobjectArray regions = NULL;
    jmethodID region_constructor, frame_constructor, create_bitmap;
    jfieldID rgba_config;
    SDL_LockMutex(ffp->subtitle_mutex);
    if (revision == ffp->subtitle_revision)
        goto done;
    snapshot = ffp->bitmap_subtitle;
    region_class = (*env)->FindClass(env, "com/vibeplayer/app/player/BitmapSubtitleRegion");
    if (!region_class) goto done;
    frame_class = (*env)->FindClass(env, "com/vibeplayer/app/player/BitmapSubtitleFrame");
    if (!frame_class) goto done;
    region_constructor = (*env)->GetMethodID(env, region_class, "<init>", "(IILandroid/graphics/Bitmap;)V");
    if (!region_constructor) goto done;
    frame_constructor = (*env)->GetMethodID(env, frame_class, "<init>", "(JII[Lcom/vibeplayer/app/player/BitmapSubtitleRegion;)V");
    if (!frame_constructor) goto done;
    regions = (*env)->NewObjectArray(env, snapshot ? snapshot->count : 0, region_class, NULL);
    if (!regions) goto done;
    if (snapshot) {
        bitmap_class = (*env)->FindClass(env, "android/graphics/Bitmap");
        if (!bitmap_class) goto done;
        config_class = (*env)->FindClass(env, "android/graphics/Bitmap$Config");
        if (!config_class) goto done;
        rgba_config = (*env)->GetStaticFieldID(env, config_class, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
        if (!rgba_config) goto done;
        config = (*env)->GetStaticObjectField(env, config_class, rgba_config);
        if (!config) {
            throw_bitmap_error(env, "Bitmap.Config.ARGB_8888 is unavailable");
            goto done;
        }
        create_bitmap = (*env)->GetStaticMethodID(env, bitmap_class, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
        if (!create_bitmap) goto done;
        for (int i = 0; i < snapshot->count; ++i) {
            BitmapRegion *source = &snapshot->regions[i];
            AndroidBitmapInfo info;
            void *pixels = NULL;
            jobject region, bitmap = (*env)->CallStaticObjectMethod(env, bitmap_class, create_bitmap,
                                                                  source->width, source->height, config);
            int converted, unlocked;
            if ((*env)->ExceptionCheck(env) || !bitmap) {
                if (bitmap) (*env)->DeleteLocalRef(env, bitmap);
                throw_bitmap_error(env, "Bitmap.createBitmap returned null");
                goto done;
            }
            if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
                info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
                info.width != (uint32_t)source->width || info.height != (uint32_t)source->height ||
                info.height == 0 || (size_t)info.stride > SIZE_MAX / info.height) {
                (*env)->DeleteLocalRef(env, bitmap);
                throw_bitmap_error(env, "Unexpected bitmap dimensions or format");
                goto done;
            }
            if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
                (*env)->DeleteLocalRef(env, bitmap);
                throw_bitmap_error(env, "Cannot lock subtitle bitmap pixels");
                goto done;
            }
            converted = vibe_bitmap_subtitle_rgba(pixels, (size_t)info.stride * info.height, info.stride,
                                                  source->indices, source->width, source->width, source->height,
                                                  source->palette, source->colors);
            unlocked = AndroidBitmap_unlockPixels(env, bitmap);
            if (converted < 0 || unlocked != ANDROID_BITMAP_RESULT_SUCCESS) {
                (*env)->DeleteLocalRef(env, bitmap);
                throw_bitmap_error(env, "Cannot convert or unlock subtitle bitmap pixels");
                goto done;
            }
            region = (*env)->NewObject(env, region_class, region_constructor, source->x, source->y, bitmap);
            (*env)->DeleteLocalRef(env, bitmap);
            if (!region) goto done;
            (*env)->SetObjectArrayElement(env, regions, i, region);
            (*env)->DeleteLocalRef(env, region);
            if ((*env)->ExceptionCheck(env)) goto done;
        }
    }
    result = (*env)->NewObject(env, frame_class, frame_constructor, (jlong)ffp->subtitle_revision,
                              snapshot ? snapshot->width : 0, snapshot ? snapshot->height : 0, regions);
done:
    if (regions) (*env)->DeleteLocalRef(env, regions);
    if (config) (*env)->DeleteLocalRef(env, config);
    if (config_class) (*env)->DeleteLocalRef(env, config_class);
    if (bitmap_class) (*env)->DeleteLocalRef(env, bitmap_class);
    if (frame_class) (*env)->DeleteLocalRef(env, frame_class);
    if (region_class) (*env)->DeleteLocalRef(env, region_class);
    SDL_UnlockMutex(ffp->subtitle_mutex);
    return result;
}
