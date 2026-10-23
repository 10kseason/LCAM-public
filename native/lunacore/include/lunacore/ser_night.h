#pragma once
#include "lunacore/raw_fusion.h"
#include <vector>

namespace lunacore {
struct SerNightPlan {
    int baseline = 0;
    int accepted_mask = 1;
    std::array<int, 6> shifts{};
    std::array<int, 4> active_area{};
    int tile_size = 64;
    int tile_columns = 0;
    int tile_rows = 0;
    // Per tile: rhoEven, modelMseRatioEven, rhoOdd, modelMseRatioOdd.
    // Opposite-parity residual estimates; heuristic bounds, never sensor calibration.
    std::vector<float> tile_bounds;
};
struct SerNightStats {
    std::uint64_t changed = 0;
    std::uint64_t lifted_cells = 0;
};
bool prepare_ser_night(const std::array<RawFrame, 3>& frames, int width, int height,
                       std::array<int, 4> active_area, SerNightPlan& plan);
// Packed output: RAW DN in low16, common-cell EV/.35*65535 in high16.
// All source buffers are immutable. False means output must not be consumed.
bool fuse_ser_night(const std::array<RawFrame, 3>& frames, int width, int height,
                    const SerNightPlan& plan, std::uint32_t* output,
                    SerNightStats& stats, bool enabled = true);
}
