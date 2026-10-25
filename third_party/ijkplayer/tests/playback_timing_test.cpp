#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>
#include "SoundTouch.h"
#include "playback_timing.h"

// Compile the actual IJK clock and frame-delay functions against a deterministic wall clock.
struct Clock { double pts, pts_drift, last_updated, speed; int serial, paused; int *queue_serial; };
struct VideoState { Clock vidclk, audclk; };
struct FFPlayer { float pf_playback_rate; int pf_playback_rate_changed; struct { double avdelay, avdiff; } stat; };
static double wall_time;
static long long av_gettime_relative() { return llround(wall_time * 1000000.0); }
#include "native_clock.inc"
#define FFMAX(a,b) ((a) > (b) ? (a) : (b))
#define FFMIN(a,b) ((a) < (b) ? (a) : (b))
#define AV_SYNC_VIDEO_MASTER 0
#define AV_SYNC_THRESHOLD_MIN 0.04
#define AV_SYNC_THRESHOLD_MAX 0.1
#define AV_SYNC_FRAMEDUP_THRESHOLD 0.1
#define AV_NOSYNC_THRESHOLD 10.0
static int get_master_sync_type(VideoState *) { return 1; }
static double get_master_clock(VideoState *is) { return get_clock(&is->audclk); }
#include "native_delay.inc"
#define AV_LOG_INFO 0
static void av_log(FFPlayer *, int, const char *, ...) { }
#include "native_rate.inc"

static void clocks_and_cadence()
{
    int serial = 1;
    for (double rate : {0.25, 0.5, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0}) {
        for (double fps : {24.0, 30.0, 60.0}) {
            wall_time = 0;
            VideoState is{};
            is.vidclk.queue_serial = is.audclk.queue_serial = &serial;
            is.vidclk.serial = is.audclk.serial = serial;
            is.vidclk.speed = is.audclk.speed = 1;
            set_clock_speed(&is.vidclk, rate);
            set_clock_speed(&is.audclk, rate);
            FFPlayer player{};
            player.pf_playback_rate = rate;
            for (int frame = 1; frame <= 1000; ++frame) {
                const double delay = compute_target_delay(&player, 1.0 / fps, &is);
                assert(fabs(delay - 1.0 / fps / rate) < 0.000005);
                wall_time += delay;
                set_clock(&is.vidclk, frame / fps, serial);
                assert(fabs(get_clock(&is.vidclk) - get_clock(&is.audclk)) < 0.00001);
            }
            assert(fabs(wall_time - 1000 / fps / rate) < 0.005);
            // Rebase a speed change without a clock jump, including a paused clock.
            double before = get_clock(&is.vidclk);
            set_clock_speed(&is.vidclk, 1.0);
            assert(fabs(before - get_clock(&is.vidclk)) < 0.000001);
            is.vidclk.paused = 1;
            before = get_clock(&is.vidclk);
            wall_time += 3;
            set_clock_speed(&is.vidclk, 2.0);
            assert(get_clock(&is.vidclk) == before);
        }
        const double pts = vibe_audio_clock_pts(10, 1920, 192000, .04, rate, .06);
        assert(fabs(pts - (10 - .06 - .05 * rate)) < 1e-10);
    }
    assert(vibe_playback_rate(NAN) == 1 && vibe_playback_rate(INFINITY) == 1);
    assert(vibe_playback_rate(0) == 1 && vibe_playback_rate(-1) == 1);
    FFPlayer player{};
    player.pf_playback_rate = 1.5f;
    for (float invalid : {NAN, INFINITY, -1.f, 0.f, 5.f}) {
        ffp_set_playback_rate(&player, invalid);
        assert(player.pf_playback_rate == 1.5f && player.pf_playback_rate_changed == 0);
    }
    ffp_set_playback_rate(&player, 2.f);
    assert(player.pf_playback_rate == 2.f && player.pf_playback_rate_changed == 1);
    puts("PASS: native clocks, 24,000 frame deadlines, pause/rebase and media-time audio latency");
}

static void soundtouch_output()
{
    constexpr int sample_rate = 48000;
    for (int channels : {1, 2}) {
        soundtouch::SoundTouch processor;
        for (float rate : {.25f, .5f, 1.f, 1.25f, 1.5f, 2.f, 3.f, 4.f}) {
            std::vector<short> result;
            auto receive = [&](const unsigned char *input, int bytes) {
                std::array<short, 66> guarded;
                guarded.fill(-12345);
                int count = vibe_soundtouch_translate(&processor, input, bytes, guarded.data() + 1,
                    32 * channels * 2, rate, channels, sample_rate);
                assert(count >= 0 && count <= 32 * channels * 2);
                assert(guarded[0] == -12345 && guarded[32 * channels + 1] == -12345);
                for (int i = 0; i < count / 2; i += channels) result.push_back(guarded[i + 1]);
                return count;
            };
            std::vector<short> input(480 * channels);
            for (int block = 0; block < 200; ++block) {
                for (int i = 0; i < 480; ++i) {
                    short sample = 12000 * sin(2 * 3.141592653589793 * 440 * (block * 480 + i) / sample_rate);
                    for (int ch = 0; ch < channels; ++ch) input[i * channels + ch] = sample;
                }
                receive(reinterpret_cast<unsigned char *>(input.data()), input.size() * 2);
                while (receive(nullptr, 0) > 0) { }
                const double pending = vibe_soundtouch_pending_seconds(&processor, sample_rate, rate);
                assert(std::isfinite(pending) && pending >= 0 && pending < 1);
                const double media_end = (block + 1) * .01;
                const double emitted_media = result.size() * rate / sample_rate;
                assert(fabs(media_end - pending - emitted_media) < .1);
            }
            processor.flush();
            while (receive(nullptr, 0) > 0) { }
            const double expected = 2.0 * sample_rate / rate;
            assert(fabs(result.size() - expected) < expected * .03);
            int crossings = 0;
            const int begin = result.size() / 5, end = result.size() * 4 / 5;
            for (int i = begin + 1; i < end; ++i) if (result[i - 1] < 0 && result[i] >= 0) ++crossings;
            const double pitch = crossings * (double)sample_rate / (end - begin);
            assert(fabs(pitch - 440) < 20);
            vibe_soundtouch_clear(&processor);
            assert(vibe_soundtouch_pending_seconds(&processor, sample_rate, rate) == 0);
            assert(receive(nullptr, 0) == 0);
        }
    }
    puts("PASS: real SoundTouch mono/stereo tempo, pitch, bounded output and queue clearing at 8 rates");
}

static void soundtouch_continuous_rates()
{
    constexpr int sample_rate = 48000, input_frames = 480;
    const std::array<float, 12> rates = {1.f, .5f, 1.f, 1.25f, 1.5f, 2.f, 3.f, 4.f, 1.f, .5f, 4.f, 1.f};
    // Include a small caller buffer so a rate change can encounter both ready output
    // and unprocessed input. The production buffer normally drains the ready output.
    for (int channels : {1, 2}) for (int capacity_frames : {32, sample_rate}) {
        soundtouch::SoundTouch processor, reference;
        std::vector<short> output(capacity_frames * channels), expected(output.size());
        std::vector<short> all_output, all_expected, input(input_frames * channels);
        double expected_frames = 0;
        int active = 0, buffered_changes = 0, blocks = 0;
        float previous_rate = 1;
        auto receive = [&](const unsigned char *bytes, int byte_count, float rate) {
            int count = vibe_soundtouch_translate(&processor, bytes, byte_count, output.data(),
                output.size() * 2, rate, channels, sample_rate);
            reference.setSampleRate(sample_rate);
            reference.setChannels(channels);
            reference.setRate(1.f);
            reference.setPitch(1.f);
            reference.setTempo(rate);
            if (bytes && byte_count) reference.putSamples(reinterpret_cast<const short *>(bytes), byte_count / (channels * 2));
            int reference_count = reference.receiveSamples(expected.data(), capacity_frames) * channels * 2;
            assert(count == reference_count);
            assert(std::equal(output.begin(), output.begin() + count / 2, expected.begin()));
            all_output.insert(all_output.end(), output.begin(), output.begin() + count / 2);
            all_expected.insert(all_expected.end(), expected.begin(), expected.begin() + reference_count / 2);
            return count;
        };
        assert(!vibe_soundtouch_should_process(0, 1.f));
        for (int cycle = 0; cycle < 4; ++cycle) for (float rate : rates) {
            if (rate != previous_rate && active &&
                processor.numUnprocessedSamples() + processor.numSamples() > 0) ++buffered_changes;
            active = vibe_soundtouch_should_process(active, rate);
            // Returning to 1x must retain ownership of the same buffered timeline.
            if (previous_rate != 1.f && rate == 1.f) assert(active);
            for (int block = 0; block < 20; ++block, ++blocks) {
                for (int i = 0; i < input_frames; ++i) {
                    const double frequency = blocks >= 950 ? 997.0 : 440.0;
                    short sample = 14000 * sin(2 * 3.141592653589793 * frequency * (blocks * input_frames + i) / sample_rate);
                    for (int ch = 0; ch < channels; ++ch) input[i * channels + ch] = sample;
                }
                if (active) {
                    receive(reinterpret_cast<const unsigned char *>(input.data()), input.size() * 2, rate);
                } else {
                    all_output.insert(all_output.end(), input.begin(), input.end());
                    all_expected.insert(all_expected.end(), input.begin(), input.end());
                }
                expected_frames += input_frames / (double)rate;
            }
            previous_rate = rate;
        }
        assert(buffered_changes > 20);
        // This verifies adapter tail preservation; the player EOF integration is separate.
        processor.flush();
        reference.flush();
        while (receive(nullptr, 0, 1.f) > 0) { }
        assert(all_output == all_expected);
        assert(fabs(all_output.size() / (double)channels - expected_frames) <= 2);
        // Locate the final 997Hz marker, allowing flush() to append its own silence.
        bool tail_marker = false;
        const int window = sample_rate / 50;
        for (int start = std::max(0, (int)all_output.size() / channels - sample_rate);
             start + window < (int)all_output.size() / channels; start += window / 2) {
            int crossings = 0;
            double energy = 0;
            for (int i = start + 1; i < start + window; ++i) {
                short previous = all_output[(i - 1) * channels], current = all_output[i * channels];
                if (previous < 0 && current >= 0) ++crossings;
                energy += (double)current * current;
            }
            if (crossings >= 17 && sqrt(energy / window) > 5000) tail_marker = true;
        }
        assert(tail_marker);
        vibe_soundtouch_clear(&processor);
        assert(vibe_soundtouch_pending_seconds(&processor, sample_rate, 1.f) == 0);
        assert(vibe_soundtouch_translate(&processor, nullptr, 0, output.data(), output.size() * 2,
            1.f, channels, sample_rate) == 0);
    }
    puts("PASS: continuous mono/stereo tempo changes retain input/output, return through 1x and preserve voiced tails");
}

int main()
{
    clocks_and_cadence();
    soundtouch_output();
    soundtouch_continuous_rates();
}
