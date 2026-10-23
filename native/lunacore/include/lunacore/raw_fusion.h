#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace lunacore {

// Four channels are Bayer raster positions TL, TR, BL, BR, not RGB ordering.
struct RawMetadata {
    std::array<double, 4> black{};
    double white = 0;
    double exposure = 1;
    std::array<double, 4> slope{};
    std::array<double, 4> intercept{};
};

struct RawFrame {
    const std::uint16_t* pixels = nullptr;
    RawMetadata metadata;
};

struct RawFusionStats {
    int baseline = 0;
    int accepted_frames = 1;
    std::array<int, 6> shifts{};
    std::uint64_t changed = 0;
    std::uint64_t merged = 0;
    std::uint64_t rejected = 0;
    double mean_effective_samples = 1;
};

// Exactly three immutable, tightly packed, native-endian Bayer frames; output
// must be a separate width*height buffer. Returns false without modifying output
// on invalid input or guide allocation failure. No resampling or demosaic.
bool fuse_raw(const std::array<RawFrame, 3>& frames, int width, int height,
              std::uint16_t* output, RawFusionStats& stats,
              std::array<int, 4> active_area = {});

}  // namespace lunacore
