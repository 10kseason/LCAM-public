#include "lunacore/raw_fusion.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <vector>

namespace {
constexpr int width = 256;
constexpr int height = 192;

void expect(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

double truth(int x, int y, int c) {
    // Flat center for an honest noise measurement; unique context for registration.
    const double structure = x > 70 && x < 180 && y > 60 && y < 130 ? 0 :
        0.055 * std::sin(x * 0.13) + 0.04 * std::cos(y * 0.19) +
        0.03 * std::sin((x + y) * 0.08);
    return 0.30 + structure + c * 0.018;
}

struct Fixture {
    std::array<std::vector<std::uint16_t>, 3> storage;
    std::array<lunacore::RawFrame, 3> frames{};
    std::vector<std::uint16_t> output = std::vector<std::uint16_t>(width * height);
    lunacore::RawFusionStats stats;

    explicit Fixture(bool shifted = false, bool exposure = false) {
        std::uint32_t seed = 20261023;
        for (int f = 0; f < 3; ++f) {
            auto& pixels = storage[f];
            pixels.resize(width * height);
            auto& m = frames[f].metadata;
            m.black = {64, 72, 80, 96};
            m.white = 4095;
            m.exposure = exposure ? 1.0 + f * 0.08 : 1;
            m.slope = {0, 0, 0, 0};
            m.intercept = {0.000025, 0.000025, 0.000025, 0.000025};
            const int dx = shifted ? f * 2 : 0;
            const int dy = shifted ? -f * 2 : 0;
            for (int y = 0; y < height; ++y) {
                for (int x = 0; x < width; ++x) {
                    const int c = (y % 2) * 2 + x % 2;
                    seed = seed * 1664525U + 1013904223U;
                    const double noise = (static_cast<int>((seed >> 16) % 71) - 35) / 4095.0;
                    const double s = truth(x - dx, y - dy, c) * m.exposure + noise;
                    pixels[y * width + x] = static_cast<std::uint16_t>(std::round(
                        s * (m.white - m.black[c]) + m.black[c]));
                }
            }
            frames[f].pixels = pixels.data();
        }
    }

    void run() {
        expect(lunacore::fuse_raw(frames, width, height, output.data(), stats), "valid input rejected");
    }

    double mse(const std::vector<std::uint16_t>& data) const {
        double error = 0;
        const auto& m = frames[stats.baseline].metadata;
        for (int y = 80; y < 110; ++y) {
            for (int x = 100; x < 150; ++x) {
                const int c = (y % 2) * 2 + x % 2;
                const double s = (data[y * width + x] - m.black[c]) /
                                 ((m.white - m.black[c]) * m.exposure);
                const double e = s - truth(x, y, c);
                error += e * e;
            }
        }
        return error / 1500;
    }
};

void static_noise_and_determinism() {
    Fixture f;
    const auto originals = f.storage;
    f.run();
    expect(f.stats.baseline == 0, "stable baseline tie break changed");
    expect(f.stats.accepted_frames == 3, "static registration failed");
    const double ratio = f.mse(f.output) / f.mse(f.storage[0]);
    std::cout << "static_noise_mse_ratio=" << ratio << '\n';
    expect(ratio < 0.8, "static noise did not decrease materially");
    expect(f.stats.changed > 1000 && f.stats.merged > 1000, "no useful fusion");
    const auto first = f.output;
    f.run();
    expect(first == f.output && originals == f.storage, "nondeterminism or source mutation");
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            if (x < 4 || y < 4 || x >= width - 4 || y >= height - 4)
                expect(f.output[y * width + x] == originals[0][y * width + x], "border modified");
            expect(std::abs(static_cast<int>(f.output[y * width + x]) -
                            originals[0][y * width + x]) <= 41, "change budget exceeded");
        }
    }
}

void translations_and_exposure() {
    Fixture f(true, true);
    f.run();
    expect(f.stats.baseline == 0, "unexpected baseline in translated fixture");
    expect(f.stats.accepted_frames == 3, "translated registration failed");
    expect(f.stats.shifts[2] == 2 && f.stats.shifts[3] == -2 &&
           f.stats.shifts[4] == 4 && f.stats.shifts[5] == -4, "wrong CFA-preserving alignment");
    const double ratio = f.mse(f.output) / f.mse(f.storage[0]);
    std::cout << "translated_exposure_mse_ratio=" << ratio << '\n';
    expect(ratio < 0.8, "normalized shifted fusion did not reduce noise");
}

void moving_region_and_saturation() {
    Fixture f;
    for (int k = 1; k < 3; ++k) {
        for (int y = 80; y < 110; ++y) {
            for (int x = 100; x < 140; ++x) f.storage[k][y * width + x] += 900;
        }
    }
    for (int k = 0; k < 3; ++k) {
        for (int y = 140; y < 160; ++y) {
            for (int x = 100; x < 120; ++x) f.storage[k][y * width + x] = 4095;
        }
    }
    f.run();
    expect(f.stats.baseline == 0, "moving patch changed baseline");
    expect(f.stats.changed > 100, "static background was not processed");
    for (int y = 85; y < 105; ++y) {
        for (int x = 105; x < 135; ++x)
            expect(f.output[y * width + x] == f.storage[0][y * width + x], "moving region ghosted");
    }
    for (int y = 140; y < 160; ++y) {
        for (int x = 100; x < 120; ++x)
            expect(f.output[y * width + x] == 4095, "saturated sample changed");
    }
}

void ambiguity_and_invalid() {
    Fixture f;
    for (auto& pixels : f.storage) std::fill(pixels.begin(), pixels.end(), uint16_t{1000});
    f.run();
    expect(f.stats.accepted_frames == 1 && f.stats.changed == 0 && f.output == f.storage[0],
           "ambiguous constant scene must keep baseline");
    const auto before = f.output;
    f.frames[1].metadata.black[2] = std::numeric_limits<double>::quiet_NaN();
    expect(!lunacore::fuse_raw(f.frames, width, height, f.output.data(), f.stats), "NaN accepted");
    expect(f.output == before, "invalid input modified output");
    expect(!lunacore::fuse_raw(f.frames, width - 1, height, f.output.data(), f.stats), "odd CFA accepted");
    f.frames[1].metadata.black[2] = 80;
    f.frames[1].metadata.intercept[0] = 0;
    expect(!lunacore::fuse_raw(f.frames, width, height, f.output.data(), f.stats), "missing noise accepted");
    f.frames[1].metadata.intercept[0] = 0.000025;
    expect(!lunacore::fuse_raw(f.frames, width, height, f.storage[0].data(), f.stats), "alias accepted");
}

void active_area_preservation() {
    Fixture f;
    const std::array<int, 4> roi{17, 13, 239, 179};
    expect(lunacore::fuse_raw(f.frames, width, height, f.output.data(), f.stats, roi), "ROI rejected");
    expect(f.stats.changed > 100, "ROI prevented all useful fusion");
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            if (x < roi[0] + 4 || y < roi[1] + 4 || x >= roi[2] - 4 || y >= roi[3] - 4)
                expect(f.output[y * width + x] == f.storage[f.stats.baseline][y * width + x],
                       "inactive area or neighborhood border changed");
        }
    }
    expect(!lunacore::fuse_raw(f.frames, width, height, f.output.data(), f.stats,
                             {-1, 0, width, height}), "negative ROI accepted");
}

void baseline_and_texture_rejection() {
    Fixture f;
    const auto sharp = f.storage[0];
    for (int y = 0; y < height; ++y) {
        for (int x = 20; x < width - 20; ++x) {
            unsigned sum = 0;
            for (int dx = -20; dx <= 20; dx += 2) sum += sharp[y * width + x + dx];
            f.storage[0][y * width + x] = static_cast<std::uint16_t>(sum / 21);
        }
    }
    f.run();
    expect(f.stats.baseline == 1, "blurred first frame was chosen over sharp frames");

    Fixture repeating;
    for (int k = 0; k < 3; ++k) {
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                repeating.storage[k][y * width + x] = static_cast<std::uint16_t>(
                    1000 + (x % 12 < 6 ? 200 : 0) + (y % 12 < 6 ? 100 : 0));
            }
        }
    }
    repeating.run();
    expect(repeating.stats.changed == 0 && repeating.stats.accepted_frames == 1,
           "repeated texture alignment ambiguity was accepted");
}

void shot_noise_only_profile() {
    Fixture f;
    // A zero read-noise intercept is valid when the shot-noise slope is positive.
    // These coefficients match device metadata; source pixels remain synthetic.
    for (auto& frame : f.frames) {
        frame.metadata.slope.fill(2.37491903165e-4);
        frame.metadata.intercept.fill(0);
    }
    const auto sources = f.storage;
    f.run();
    expect(f.stats.accepted_frames == 3 && f.stats.changed > 1000,
           "valid shot-noise-only profile rejected or not merged");
    expect(f.mse(f.output) < f.mse(f.storage[f.stats.baseline]),
           "shot-noise-only profile did not reduce synthetic noise");
    expect(f.storage == sources, "shot-noise-only processing modified sources");
    const auto result = f.output;
    f.frames[1].metadata.intercept[2] = -1e-8;
    expect(!lunacore::fuse_raw(f.frames, width, height, f.output.data(), f.stats),
           "negative noise intercept accepted");
    expect(f.output == result, "rejected negative intercept modified output");
    f.frames[1].metadata.intercept[2] = 0;
    f.frames[1].metadata.slope[2] = 0;
    expect(!lunacore::fuse_raw(f.frames, width, height, f.output.data(), f.stats),
           "all-zero noise profile accepted");
    expect(f.output == result, "rejected zero profile modified output");
}
}  // namespace

int main() {
    try {
        static_noise_and_determinism();
        translations_and_exposure();
        moving_region_and_saturation();
        ambiguity_and_invalid();
        active_area_preservation();
        baseline_and_texture_rejection();
        shot_noise_only_profile();
        std::cout << "RAW fusion: 7 scenarios passed\n";
        return 0;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
