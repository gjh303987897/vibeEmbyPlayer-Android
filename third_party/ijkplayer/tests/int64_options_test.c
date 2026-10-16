/* Regression for IJK context pointers transported through FFmpeg dictionaries.
 * Link against the packaged libijkffmpeg.so and run on Android; no pointer in
 * this test is dereferenced. Keep Android heap pointer tagging enabled.
 */
#include <inttypes.h>
#include <limits.h>
#include <stddef.h>
#include <stdio.h>

#include "libavutil/dict.h"
#include "libavutil/opt.h"

typedef struct Options {
    const AVClass *av_class;
    int64_t context;
    int64_t bounded;
} Options;

static const AVOption options[] = {
    { "ijkapplication", "context pointer", offsetof(Options, context),
      AV_OPT_TYPE_INT64, { .i64 = 0 }, (double)INT64_MIN, (double)INT64_MAX, 0 },
    { "bounded", "bounded integer", offsetof(Options, bounded),
      AV_OPT_TYPE_INT64, { .i64 = 0 }, 0, 10, 0 },
    { NULL }
};

static const AVClass option_class = {
    .class_name = "int64-pointer-regression",
    .item_name = av_default_item_name,
    .option = options,
    .version = LIBAVUTIL_VERSION_INT
};

int main(void)
{
    static const int64_t values[] = {
        0, 1, -1, INT64_MAX, INT64_MIN,
        INT64_C(9007199254740993), -INT64_C(9007199254740993),
        (int64_t)UINT64_C(0xb40000793243f2e0),
        (int64_t)UINT64_C(0xb4000079c081cc60)
    };
    Options context = { .av_class = &option_class };
    int failures = 0;
    for (size_t i = 0; i < sizeof(values) / sizeof(values[0]); ++i) {
        AVDictionary *dictionary = NULL;
        char decimal[32];
        int64_t evaluated = 0;
        snprintf(decimal, sizeof(decimal), "%" PRId64, values[i]);
        if (av_dict_set_int(&dictionary, "ijkapplication", values[i], 0) < 0 ||
            av_opt_set_dict(&context, &dictionary) < 0 || dictionary ||
            context.context != values[i]) {
            fprintf(stderr, "dictionary pointer changed: expected %016" PRIx64
                    ", got %016" PRIx64 "\n", (uint64_t)values[i],
                    (uint64_t)context.context);
            ++failures;
        }
        av_dict_free(&dictionary);
        if (av_opt_eval_int64(&context, &options[0], decimal, &evaluated) < 0 ||
            evaluated != values[i]) {
            fprintf(stderr, "int64 evaluator changed %s\n", decimal);
            ++failures;
        }
    }

    if (av_opt_set(&context, "ijkapplication", "6*7", 0) < 0 || context.context != 42 ||
        av_opt_set(&context, "ijkapplication", "0x1234", 0) < 0 || context.context != 0x1234 ||
        av_opt_set(&context, "ijkapplication", "010", 0) < 0 || context.context != 10) {
        fprintf(stderr, "existing numeric expressions changed\n");
        ++failures;
    }
    context.context = 123;
    if (av_opt_set(&context, "ijkapplication", "9223372036854775808", 0) >= 0 ||
        av_opt_set(&context, "ijkapplication", "-9223372036854775809", 0) >= 0 ||
        context.context != 123) {
        fprintf(stderr, "int64 overflow was accepted\n");
        ++failures;
    }
    if (av_opt_set(&context, "bounded", "10", 0) < 0 || context.bounded != 10 ||
        av_opt_set(&context, "bounded", "11", 0) >= 0 || context.bounded != 10 ||
        av_opt_set(&context, "bounded", "-1", 0) >= 0 || context.bounded != 10) {
        fprintf(stderr, "integer range checks changed\n");
        ++failures;
    }
    printf("int64 option regression: %s (%d failures)\n", failures ? "FAIL" : "PASS", failures);
    return failures ? 1 : 0;
}
