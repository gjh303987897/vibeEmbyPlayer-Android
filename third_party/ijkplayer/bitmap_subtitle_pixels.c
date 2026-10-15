#include "bitmap_subtitle_pixels.h"
#include <errno.h>
#include <limits.h>

int vibe_bitmap_subtitle_rgba(uint8_t *destination, size_t destination_size,
                             size_t destination_stride, const uint8_t *indices,
                             ptrdiff_t index_stride, int width, int height,
                             const uint32_t *palette, int palette_count)
{
    uint8_t rgba[256][4];
    size_t row_bytes;
    if (!destination || !indices || !palette || width <= 0 || height <= 0 ||
        palette_count <= 0 || palette_count > 256 || (size_t)width > SIZE_MAX / 4)
        return -EINVAL;
    row_bytes = (size_t)width * 4;
    if (destination_stride < row_bytes || destination_size < row_bytes ||
        (size_t)(height - 1) > (destination_size - row_bytes) / destination_stride ||
        index_stride == PTRDIFF_MIN ||
        (index_stride < 0 ? -index_stride : index_stride) < width ||
        (height > 1 && (index_stride < 0 ? -index_stride : index_stride) > PTRDIFF_MAX / (height - 1)))
        return -EINVAL;
    for (int i = 0; i < palette_count; ++i) {
        uint32_t color = palette[i];
        unsigned alpha = color >> 24;
        rgba[i][0] = (((color >> 16) & 255) * alpha + 127) / 255;
        rgba[i][1] = (((color >> 8) & 255) * alpha + 127) / 255;
        rgba[i][2] = ((color & 255) * alpha + 127) / 255;
        rgba[i][3] = alpha;
    }
    for (int y = 0; y < height; ++y) {
        uint8_t *output = destination + (size_t)y * destination_stride;
        const uint8_t *input = indices + (ptrdiff_t)y * index_stride;
        for (int x = 0; x < width; ++x) {
            unsigned index = input[x];
            if (index >= (unsigned)palette_count)
                return -EINVAL;
            output[4 * (size_t)x] = rgba[index][0];
            output[4 * (size_t)x + 1] = rgba[index][1];
            output[4 * (size_t)x + 2] = rgba[index][2];
            output[4 * (size_t)x + 3] = rgba[index][3];
        }
    }
    return 0;
}
