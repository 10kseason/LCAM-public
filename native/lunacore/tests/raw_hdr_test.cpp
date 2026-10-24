#include "lunacore/raw_hdr.h"

#include <algorithm>
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <vector>

namespace {
constexpr int w = 256, h = 192;
void expect(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}

double scene(int x, int y, int c) {
    if (x > 70 && x < 180 && y > 65 && y < 125) return 0.12 + c * 0.008;
    return 0.22 + 0.07 * std::sin(x * 0.13) + 0.06 * std::cos(y * 0.19) +
        0.05 * std::sin((x + y) * 0.08) + c * 0.008;
}

struct Fixture {
    std::array<std::vector<std::uint16_t>, 3> storage;
    std::array<lunacore::RawFrame, 3> frames{};
    std::vector<std::uint32_t> output = std::vector<std::uint32_t>(w * h, 0xdeadbeefU);
    lunacore::RawHdrStats stats;
    explicit Fixture(bool shifted = false) {
        std::uint32_t seed = 42;
        for (int f = 0; f < 3; ++f) {
            auto& m = frames[f].metadata;
            m.black = {64, 68, 72, 76};
            m.white = 1023;
            m.exposure = std::array<double, 3>{1, .25, 4}[f];
            m.slope = {0, 0, 0, 0};
            m.intercept = {0.000004, 0.000004, 0.000004, 0.000004};
            storage[f].resize(w * h);
            const int dx = shifted && f != 1 ? (f == 0 ? 4 : -2) : 0;
            const int dy = shifted && f != 1 ? (f == 0 ? -2 : 4) : 0;
            for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
                const int c = y % 2 * 2 + x % 2;
                seed = seed * 1664525U + 1013904223U;
                const double noise = (static_cast<int>((seed >> 16) % 7) - 3) / 1023.0;
                const double value = m.black[c] + (m.white - m.black[c]) *
                    (m.exposure * scene(x - dx, y - dy, c) + noise);
                storage[f][y * w + x] = static_cast<std::uint16_t>(std::lround(
                    std::clamp(value, 0.0, m.white)));
            }
            frames[f].pixels = storage[f].data();
        }
    }
    void run() {
        expect(lunacore::fuse_raw_hdr(frames, w, h, output.data(), stats), "valid metadata rejected");
    }
    std::uint32_t reference(int p) const {
        const int c = p / w % 2 * 2 + p % w % 2;
        const auto& m = frames[1].metadata;
        return static_cast<std::uint32_t>(std::lround(std::clamp(
            (storage[1][p] - m.black[c]) / (m.white - m.black[c]), 0.0, 1.0) * 65535));
    }
};

void brackets_and_precision(bool shifted) {
    Fixture f(shifted);
    const auto original = f.storage;
    f.run();
    expect(f.stats.reference == 1 && f.stats.accepted_mask == 7, "bracket registration failed");
    expect(f.stats.merged_pixels > 1000, "no actual HDR fusion");
    if (shifted) expect(f.stats.shifts == std::array<int, 6>{4, -2, 0, 0, -2, 4},
                        "CFA translation mismatch");
    double before = 0, after = 0;
    int sub_dn = 0;
    for (int y = 80; y < 110; ++y) for (int x = 100; x < 150; ++x) {
        const int p = y * w + x, c = y % 2 * 2 + x % 2;
        const auto& m = f.frames[1].metadata;
        const double truth = scene(x, y, c) * .25;
        before += std::pow(f.reference(p) / 65535.0 - truth, 2);
        after += std::pow(f.output[p] / 65535.0 - truth, 2);
        const double dn = f.output[p] / 65535.0 * (m.white - m.black[c]) + m.black[c];
        if (std::abs(dn - std::round(dn)) > .1) ++sub_dn;
    }
    std::cout << "HDR shadow MSE ratio=" << after / before << " sub-DN=" << sub_dn << '\n';
    expect(after < before * .5, "long exposure did not improve short-frame shadow noise");
    expect(sub_dn > 500, "packing lost sub-DN shadow precision");
    for (int x = 0; x < w; ++x) expect(f.output[x] == f.reference(x), "border changed");
    for (auto value : f.output) expect(value <= 65535, "Night lift bits contaminated");
    const auto once = f.output;
    f.run();
    expect(once == f.output && original == f.storage, "nondeterminism or source mutation");
}

void moving_and_clipped_regions() {
    Fixture f;
    for (int y = 80; y < 110; ++y) for (int x = 100; x < 145; ++x) {
        f.storage[0][y * w + x] += 150;
        f.storage[2][y * w + x] += 150;
    }
    // Bright sky is present only in the short frame; longer exposures are clipped.
    for (int y = 135; y < 155; ++y) for (int x = 100; x < 140; ++x) {
        f.storage[0][y * w + x] = 1023;
        f.storage[2][y * w + x] = 1023;
        f.storage[1][y * w + x] = static_cast<std::uint16_t>(700 + x - 100);
    }
    f.run();
    expect(f.stats.accepted_mask == 7 && f.stats.merged_pixels > 1000, "static context lost");
    for (int y = 85; y < 105; ++y) for (int x = 105; x < 135; ++x)
        expect(f.output[y * w + x] == f.reference(y * w + x), "moving object ghosted");
    for (int y = 138; y < 152; ++y) for (int x = 105; x < 135; ++x)
        expect(f.output[y * w + x] == f.reference(y * w + x), "clipped sky contaminated");
    expect(f.output[140 * w + 130] > f.output[140 * w + 110], "sky gradient disappeared");
}

void flat_fallback_and_roi() {
    Fixture f;
    for (int k = 0; k < 3; ++k) for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
        const auto& m = f.frames[k].metadata;
        const int c = y % 2 * 2 + x % 2;
        f.storage[k][y * w + x] = static_cast<std::uint16_t>(std::lround(
            m.black[c] + .1 * m.exposure * (m.white - m.black[c])));
    }
    f.run();
    expect(f.stats.accepted_mask == 2 && f.stats.merged_pixels == 0, "ambiguous flat accepted");
    for (int p = 0; p < w * h; ++p) expect(f.output[p] == f.reference(p), "flat fallback changed");
    Fixture roi;
    expect(lunacore::fuse_raw_hdr(roi.frames, w, h, roi.output.data(), roi.stats,
                                {20, 20, w - 20, h - 20}), "valid ROI rejected");
    for (int y = 0; y < 20; ++y) for (int x = 0; x < w; ++x)
        expect(roi.output[y * w + x] == roi.reference(y * w + x), "outside ROI modified");
}

void invalid_metadata_is_atomic() {
    Fixture f;
    const auto original = f.output;
    const auto valid = f.frames;
    for (int failure = 0; failure < 7; ++failure) {
        f.frames = valid;
        auto& m = f.frames[0].metadata;
        if (failure == 0) m.exposure = std::numeric_limits<double>::quiet_NaN();
        if (failure == 1) m.exposure = 64;
        if (failure == 2) m.black[0] = 1023;
        if (failure == 3) m.intercept[0] = 0;
        if (failure == 4) m.slope[0] = -1;
        if (failure == 5) m.white = std::numeric_limits<double>::infinity();
        if (failure == 6) f.frames[0].pixels = nullptr;
        expect(!lunacore::fuse_raw_hdr(f.frames, w, h, f.output.data(), f.stats),
               "invalid metadata accepted");
        expect(f.output == original, "invalid input partially mutated output");
    }
    f.frames = valid;
    expect(!lunacore::fuse_raw_hdr(f.frames, w, h, f.output.data(), f.stats, {0, 0, 32, 32}),
           "invalid ROI accepted");
    expect(f.output == original, "invalid ROI mutated output");
}
}  // namespace

int main() {
    try {
        brackets_and_precision(false);
        brackets_and_precision(true);
        moving_and_clipped_regions();
        flat_fallback_and_roi();
        invalid_metadata_is_atomic();
        std::cout << "RAW HDR tests passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
