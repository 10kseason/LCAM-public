#pragma once
#include <cstddef>
#include <cstdint>

namespace lunacore {
enum class HdrError { none, invalid_image, allocation_failed };
enum class HdrReason { aligned, no_highlight_evidence, ambiguous_alignment,
                       inconsistent_alignment, exposure_mismatch,
                       calibration_insufficient, calibration_rejected };
struct HdrStats {
    std::uint64_t changed = 0;
    bool accepted = false;
    // On rejection these are the last best candidate, not a validated transform.
    int shift_x = 0;
    int shift_y = 0;
    HdrReason reason = HdrReason::ambiguous_alignment;
    int alignment_anchors = 0;
    double alignment_cost = 1;
    double alignment_margin = 0;
    int calibration_samples = 0;
    int calibration_knots = 0;
    double calibration_inlier_fraction = 0;
    double calibration_rmse = 0;
    double calibration_min_luma = 0;
    double calibration_max_luma = 0;
};
// Distinct RGBA buffers, equal dimensions. Only base is writable. Translation offset
// means candidate(x + shift_x, y + shift_y) corresponds to base(x, y).
HdrError hdr_fuse_rgba(std::uint8_t* base, std::size_t base_stride,
                      const std::uint8_t* shorter, std::size_t short_stride,
                      int width, int height, double exposure_ratio, HdrStats& stats) noexcept;
}
