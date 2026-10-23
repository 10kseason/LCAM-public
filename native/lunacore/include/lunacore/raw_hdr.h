#pragma once

#include "lunacore/raw_fusion.h"

namespace lunacore {

struct RawHdrStats {
    int reference = 0;
    int accepted_mask = 0;
    std::array<int, 6> shifts{};
    std::uint64_t merged_pixels = 0;
    std::uint64_t rejected_cells = 0;
};

// Three immutable exposures of one sensor/CFA. Exposure is actual time*ISO,
// relative to the metered baseline. Output low16 is short-reference normalized
// RAW [0,65535], black=0, white=65535; high16 is zero (no Night lift).
// This preserves sub-DN shadow information, but is for JPEG rendering, not DNG.
// Reference color/LSC calibration still applies; gain is 1/reference.exposure.
// Invalid metadata returns false before output mutation. Uncertain alignment or
// local motion preserves normalized shortest-reference samples instead of ghosts.
bool fuse_raw_hdr(const std::array<RawFrame, 3>& frames, int width, int height,
                  std::uint32_t* output, RawHdrStats& stats,
                  std::array<int, 4> active_area = {});

}  // namespace lunacore
