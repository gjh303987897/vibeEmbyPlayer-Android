#ifndef VIBE_BITMAP_SUBTITLE_PIXELS_H
#define VIBE_BITMAP_SUBTITLE_PIXELS_H

#include <stddef.h>
#include <stdint.h>

/* Palette entries are FFmpeg's native-endian 0xAARRGGBB. Destination bytes are
 * premultiplied RGBA_8888. Both strides describe the distance between rows;
 * indices points at the first displayed row even when its stride is negative.
 * Returns 0 or -EINVAL, including an index outside palette_count. */
int vibe_bitmap_subtitle_rgba(uint8_t *destination, size_t destination_size,
                             size_t destination_stride, const uint8_t *indices,
                             ptrdiff_t index_stride, int width, int height,
                             const uint32_t *palette, int palette_count);

#endif
