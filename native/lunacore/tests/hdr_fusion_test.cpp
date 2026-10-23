#include "lunacore/hdr_fusion.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <vector>

namespace {
void check(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
std::uint8_t encode(double value) {
    value = std::clamp(value, 0.0, 1.0);
    const double s = value <= 0.0031308 ? value * 12.92 : 1.055 * std::pow(value, 1 / 2.4) - 0.055;
    return static_cast<std::uint8_t>(std::clamp(std::lround(s * 255), 0L, 255L));
}
std::array<double, 3> scene(int x, int y, bool highlights) {
    const auto xx = static_cast<std::uint32_t>((x + 10000) / 5);
    const auto yy = static_cast<std::uint32_t>((y + 10000) / 5);
    const auto hash = (xx * 1664525U) ^ (yy * 1013904223U) ^ (xx * yy * 31U);
    const double middle = 0.12 + (hash % 450) / 1000.0;
    if (highlights && x % 256 >= 96 && x % 256 < 160 && y % 256 >= 96 && y % 256 < 160) {
        const double detail = 1.2 + (x % 8) * 0.16 + (y % 7) * 0.02;
        return {detail, detail, detail};
    }
    return {middle, middle * 0.9, middle * 0.8};
}
struct Fixture {
    int width, height;
    std::size_t stride;
    std::vector<std::uint8_t> base, shorter;
};
Fixture fixture(int w, int h, int dx = 0, int dy = 0, bool highlights = true, bool nonlinear = false) {
    Fixture f{w, h, static_cast<std::size_t>(w) * 4 + 7, {}, {}};
    f.base.assign(f.stride * h, 83);
    f.shorter = f.base;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const auto i = y * f.stride + 4 * x;
            const auto a = scene(x, y, highlights), b = scene(x - dx, y - dy, highlights);
            const double short_luma = (0.2126 * b[0] + 0.7152 * b[1] + 0.0722 * b[2]) / 4;
            const double gain = nonlinear ? 0.8 * std::pow(short_luma, 0.75) / short_luma : 1;
            for (int c = 0; c < 3; ++c) {
                f.base[i + c] = encode(a[c]);
                f.shorter[i + c] = encode(b[c] / 4 * gain);
            }
            f.base[i + 3] = f.shorter[i + 3] = 255;
        }
    }
    return f;
}
Fixture coarse_alias_fixture(bool repeated_fine, int noise, int dx = 0, int dy = 0) {
    auto f = fixture(1024, 768, 0, 0, false);
    for (int y = 0; y < f.height; ++y) {
        for (int x = 0; x < f.width; ++x) {
            const unsigned tx = static_cast<unsigned>(x % 96 / 8), ty = static_cast<unsigned>(y / 8);
            const unsigned coarse = tx * 1664525U ^ ty * 1013904223U ^ tx * ty * 31U;
            const unsigned fx = static_cast<unsigned>((repeated_fine ? x % 96 : x) / 4);
            const unsigned fy = static_cast<unsigned>(y / 4);
            const unsigned detail = fx * 22695477U ^ fy * 1103515245U ^ fx * fy * 73U;
            // Every 4x4 block has eight +detail and eight -detail pixels.
            // The box guide loses their unique phase and aliases at +/-96px.
            const int phase = ((x % 4 < 2) ^ (y % 4 < 2) ^ ((detail >> 7) & 1U)) ? 1 : -1;
            const int a = 105 + static_cast<int>(coarse % 80) + phase * 24;
            const double encoded = a / 255.0;
            const double linear = std::pow((encoded + 0.055) / 1.055, 2.4);
            int b = encode(0.8 * std::pow(linear / 4, 0.75));
            b += noise * (((x * 37 + y * 19) % 3) - 1);
            const auto i = y * f.stride + x * 4;
            for (int c = 0; c < 3; ++c) {
                f.base[i + c] = static_cast<std::uint8_t>(a);
                f.shorter[i + c] = static_cast<std::uint8_t>(b);
            }
        }
    }
    if (dx != 0 || dy != 0) {
        const auto unshifted = f.shorter;
        for (int y = 0; y < f.height; ++y) {
            for (int x = 0; x < f.width; ++x) {
                const int sx = x - dx, sy = y - dy;
                if (sx < 0 || sx >= f.width || sy < 0 || sy >= f.height) continue;
                for (int c = 0; c < 4; ++c)
                    f.shorter[y * f.stride + x * 4 + c] = unshifted[sy * f.stride + sx * 4 + c];
            }
        }
    }
    return f;
}
lunacore::HdrStats run(Fixture& f, double ratio = 4) {
    lunacore::HdrStats stats;
    check(lunacore::hdr_fuse_rgba(f.base.data(), f.stride, f.shorter.data(), f.stride,
          f.width, f.height, ratio, stats) == lunacore::HdrError::none, "merge error");
    std::cout << "reason=" << static_cast<int>(stats.reason) << " changed=" << stats.changed
              << " anchors=" << stats.alignment_anchors << " cost=" << stats.alignment_cost
              << " margin=" << stats.alignment_margin << " samples=" << stats.calibration_samples
              << " knots=" << stats.calibration_knots << " inliers=" << stats.calibration_inlier_fraction
              << " rmse=" << stats.calibration_rmse << " shift=" << stats.shift_x << ',' << stats.shift_y << '\n';
    return stats;
}
}

int main(int argc, char** /* argv */) {
    try {
        {
            auto f = fixture(256, 256);
            const auto original = f.base, shorter = f.shorter;
            const auto stats = run(f);
            check(stats.accepted && stats.changed >= 256, "supported highlight recovery missing");
            check(stats.shift_x == 0 && stats.shift_y == 0, "zero-shift alignment");
            check(f.shorter == shorter, "short input modified");
            check(f.base[128 * f.stride + 104 * 4] < f.base[128 * f.stride + 110 * 4],
                  "short-frame highlight detail ordering lost");
            check(f.base[128 * f.stride + 110 * 4] == original[128 * f.stride + 110 * 4],
                  "unsupported highlight extrapolation changed base");
            for (int y = 0; y < f.height; ++y) {
                for (int x = 0; x < f.width; ++x) {
                    const auto i = y * f.stride + x * 4;
                    if (original[i] < 235) {
                        check(std::equal(original.begin() + i, original.begin() + i + 4,
                                         f.base.begin() + i), "midtones changed");
                    }
                }
                check(std::equal(original.begin() + y * f.stride + f.width * 4,
                                 original.begin() + (y + 1) * f.stride,
                                 f.base.begin() + y * f.stride + f.width * 4), "padding changed");
            }
        }
        {
            auto f = fixture(256, 256, 5, -3);
            const auto stats = run(f);
            check(stats.accepted && stats.shift_x == 5 && stats.shift_y == -3,
                  "translation recovery failed");
        }
        {
            auto f = fixture(256, 256, 0, 0, false);
            const auto original = f.base;
            const auto stats = run(f);
            check(stats.changed == 0 && f.base == original, "non-HDR scene must be unchanged");
        }
        {
            auto f = fixture(256, 256);
            for (int y = 96; y < 160; ++y) {
                for (int x = 96; x < 160; ++x) {
                    const auto i = y * f.stride + x * 4;
                    f.shorter[i] = f.shorter[i + 1] = f.shorter[i + 2] = encode(0.1);
                }
            }
            const auto original = f.base;
            run(f);
            check(f.base == original, "moving/mismatched highlight contaminated base");
        }
        {
            auto f = fixture(256, 256);
            for (int y = 0; y < f.height; ++y) {
                for (int x = 0; x < f.width; ++x) {
                    const auto i = y * f.stride + x * 4;
                    for (int c = 0; c < 3; ++c) { f.base[i + c] = encode(0.4); f.shorter[i + c] = encode(0.1); }
                }
            }
            const auto original = f.base;
            const auto stats = run(f);
            check(!stats.accepted && f.base == original, "ambiguous flat scene accepted");
        }
        {
            auto f = fixture(256, 256, 5, -3, true, true);
            const auto original = f.base;
            const auto stats = run(f);
            check(stats.accepted && stats.changed >= 256 && stats.shift_x == 5 && stats.shift_y == -3,
                  "nonlinear ISP response recovery failed");
            check(stats.calibration_inlier_fraction >= 0.85 && stats.calibration_rmse < 0.035,
                  "nonlinear held-out fit invalid");
            for (int y = 0; y < f.height; ++y) {
                for (int x = 0; x < f.width; ++x) {
                    const auto i = y * f.stride + x * 4;
                    if (original[i] < 235) check(std::equal(original.begin() + i, original.begin() + i + 4,
                        f.base.begin() + i), "nonlinear fixture midtone changed");
                }
            }
        }
        {
            auto f = fixture(256, 256, 24, 0);
            const auto original = f.base;
            check(!run(f).accepted && f.base == original, "out-of-search misalignment accepted");
        }
        for (const auto shift : {std::array<int, 2>{40, -24}, {81, -43}, {100, 60}}) {
            // Odd dimensions exercise pyramid truncation, non-four-pixel shifts
            // exercise refinement, and the final pixels still use native dimensions.
            auto f = fixture(1027, 769, shift[0], shift[1], true, true);
            const auto original = f.base, shorter = f.shorter;
            const auto stats = run(f);
            check(stats.accepted && stats.shift_x == shift[0] && stats.shift_y == shift[1],
                  "multiscale large translation recovery failed");
            check(stats.changed > 256 && f.shorter == shorter, "large-shift evidence or readonly input failed");
            for (int y = 0; y < f.height; ++y) {
                for (int x = 0; x < f.width; ++x) {
                    const auto i = y * f.stride + x * 4;
                    const bool outside = x + shift[0] < 0 || x + shift[0] >= f.width ||
                        y + shift[1] < 0 || y + shift[1] >= f.height;
                    if (outside || original[i] < 235) check(std::equal(original.begin() + i,
                        original.begin() + i + 4, f.base.begin() + i), "large-shift border or midtone changed");
                }
            }
        }
        for (const int dx : {128, 140}) {
            auto f = fixture(1024, 768, dx, 0);
            const auto original = f.base;
            check(!run(f).accepted && f.base == original, "pyramid boundary/out-of-range shift accepted");
        }
        for (const auto configuration : {std::array<int, 3>{0, 0, 0}, {1, 1, -1}, {2, 3, 2}}) {
            auto f = coarse_alias_fixture(false, configuration[0], configuration[1], configuration[2]);
            const auto original = f.base, shorter = f.shorter;
            const auto stats = run(f);
            check(stats.shift_x == configuration[1] && stats.shift_y == configuration[2] &&
                  stats.calibration_samples > 176,
                  "coarse-aliased nonlinear/noisy scene did not reach calibrated full-resolution alignment");
            check(stats.reason == lunacore::HdrReason::no_highlight_evidence && f.base == original &&
                  f.shorter == shorter, "coarse-aliased midtones or readonly input changed");
        }
        {
            auto f = coarse_alias_fixture(true, false);
            const auto original = f.base;
            const auto stats = run(f);
            check(!stats.accepted && stats.calibration_samples == 0 && f.base == original,
                  "full-resolution repetitive alternatives were hidden by candidate selection");
        }
        for (const int block_size : {8, 10}) {
            auto f = fixture(1024, 768);
            for (int y = 0; y < f.height; ++y) {
                for (int x = 0; x < f.width; ++x) {
                    const auto i = y * f.stride + x * 4;
                    const double value = ((x / block_size + y / block_size) % 2) ? 0.5 : 0.15;
                    for (int c = 0; c < 3; ++c) {
                        f.base[i + c] = encode(value); f.shorter[i + c] = encode(value / 4);
                    }
                }
            }
            const auto original = f.base;
            const auto stats = run(f);
            check(!stats.accepted && f.base == original && stats.calibration_samples == 0,
                  "repetitive pyramid correspondence accepted");
            if (block_size == 10) {
                // Period20 does not coincide with the +/-128 search boundary:
                // five in-band interior basins must exhaust the proposal budget.
                check(std::abs(stats.shift_x) < 128 && std::abs(stats.shift_y) < 128 &&
                      stats.alignment_cost == 0 && stats.alignment_margin == 0,
                      "candidate budget rejection not exercised away from search boundary");
            }
        }
        {
            auto f = fixture(256, 256);
            // Spatially varying ISP responses preserve local ordering but have no
            // single radiometric consensus. Held-out validation must reject them.
            for (int y = 0; y < f.height; ++y) {
                for (int x = 0; x < f.width; ++x) {
                    const auto b = scene(x, y, true);
                    const auto i = y * f.stride + x * 4;
                    const double spatial_gain = 1 + 0.35 * std::sin(x / 25.0) * std::sin(y / 35.0);
                    for (int c = 0; c < 3; ++c) f.shorter[i + c] = encode(b[c] / 4 * spatial_gain);
                }
            }
            const auto original = f.base;
            const auto stats = run(f);
            check(!stats.accepted && f.base == original, "inconsistent response accepted");
            check(stats.reason == lunacore::HdrReason::calibration_rejected,
                  "inconsistent response not rejected by calibration");
            check(stats.calibration_rmse > 0.035, "held-out response rejection was not exercised");
        }
        {
            auto f = fixture(256, 256);
            std::swap(f.base, f.shorter);
            const auto original = f.base;
            const auto stats = run(f);
            check(!stats.accepted && f.base == original, "reversed exposure accepted");
            lunacore::HdrStats result;
            check(lunacore::hdr_fuse_rgba(f.base.data(), f.stride, f.shorter.data(), f.stride,
                  f.width, f.height, std::numeric_limits<double>::quiet_NaN(), result) ==
                  lunacore::HdrError::invalid_image, "invalid ratio not rejected");
            check(lunacore::hdr_fuse_rgba(f.base.data(), f.stride, f.base.data(), f.stride,
                  f.width, f.height, 4, result) == lunacore::HdrError::invalid_image, "alias accepted");
        }
        if (argc > 1) {
            auto f = fixture(4080, 3060);
            const auto start = std::chrono::steady_clock::now();
            const auto stats = run(f);
            const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - start).count();
            std::cout << "12MP desktop native benchmark: " << elapsed << " ms, "
                      << stats.changed << " changed pixels, reason " << static_cast<int>(stats.reason)
                      << ", shift " << stats.shift_x << "," << stats.shift_y << "\n";
            check(stats.accepted && stats.changed > 10000, "12MP highlight evidence missing");
        }
        std::cout << "HDR: recovered actual short-frame highlight ordering, translation, unchanged midtones, "
                     "motion fallback, ambiguity, exposure and validation checks passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
