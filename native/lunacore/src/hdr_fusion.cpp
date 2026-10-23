#include "lunacore/hdr_fusion.h"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdlib>
#include <limits>
#include <memory>

namespace lunacore {
namespace {
constexpr int kSearch = 16;
constexpr int kCoarseSearch = 32;
constexpr int kGrid = 32;
struct Tables {
    std::array<float, 256> linear{};
    std::array<std::uint8_t, 65536> encoded{};
    Tables() {
        for (int i = 0; i < 256; ++i) {
            const double s = i / 255.0;
            linear[i] = static_cast<float>(s <= 0.04045 ? s / 12.92 :
                                           std::pow((s + 0.055) / 1.055, 2.4));
        }
        for (int i = 0; i < 65536; ++i) {
            const double s = i / 65535.0;
            const double e = s <= 0.0031308 ? 12.92 * s : 1.055 * std::pow(s, 1 / 2.4) - 0.055;
            encoded[i] = static_cast<std::uint8_t>(std::clamp(std::lround(e * 255), 0L, 255L));
        }
    }
};
struct Image {
    const std::uint8_t* data;
    std::size_t stride;
    int width, height;
    const std::uint8_t* at(int x, int y) const { return data + y * stride + x * 4; }
};
float luminance(const std::uint8_t* p, const Tables& t, float scale) {
    return (0.2126f * t.linear[p[0]] + 0.7152f * t.linear[p[1]] +
            0.0722f * t.linear[p[2]]) * scale;
}
int grey(const std::uint8_t* p) { return (77 * p[0] + 150 * p[1] + 29 * p[2]) >> 8; }
struct Guide {
    const Image& image;
    int scale;
    const std::uint8_t* cached = nullptr;
    int width() const { return image.width / scale; }
    int height() const { return image.height / scale; }
    int at(int x, int y) const {
        if (cached) return cached[y * width() + x];
        int sum = 0;
        for (int oy = 0; oy < scale; ++oy) {
            for (int ox = 0; ox < scale; ++ox) {
                const auto* p = image.at(x * scale + ox, y * scale + oy);
                if (p[3] != 255) return 0;
                sum += grey(p);
            }
        }
        return (sum + scale * scale / 2) / (scale * scale);
    }
};
struct Census { unsigned bits = 0, mask = 0; };
Census census(const Guide& image, int x, int y) {
    constexpr int offsets[8][2] = {{-3,0},{3,0},{0,-3},{0,3},{-5,-5},{5,-5},{-5,5},{5,5}};
    Census result;
    const int value = image.at(x, y);
    if (value < 10 || value > 240) return result;
    for (unsigned i = 0; i < 8; ++i) {
        const int neighbor = image.at(x + offsets[i][0], y + offsets[i][1]);
        const int delta = neighbor - value;
        if (neighbor > 0 && std::abs(delta) >= 2) result.mask |= 1U << i;
        if (delta > 0) result.bits |= 1U << i;
    }
    return result;
}
struct Anchor { int x, y; Census value; };
struct Alignment {
    int x = 0, y = 0, anchors = 0;
    bool accepted = false;
    double cost = 1, margin = 0;
};

// Relative neighborhood ordering survives a monotone ISP response; an exposure
// model must not decide geometry before corresponding samples have been found.
Alignment align(const Guide& source, const Guide& target, int radius,
                int center_x = 0, int center_y = 0,
                std::array<Alignment, 5>* proposals = nullptr,
                int common_border_x = 0, int common_border_y = 0) {
    std::array<Anchor, 512> anchors{};
    int count = 0;
    // All hypotheses use the same overlap and anchors, including at image borders.
    const int border_x = std::max(common_border_x, radius + std::abs(center_x) + 6);
    const int border_y = std::max(common_border_y, radius + std::abs(center_y) + 6);
    const int sx = std::max(1, (source.width() - border_x * 2) / 24);
    const int sy = std::max(1, (source.height() - border_y * 2) / 16);
    for (int cell_y = border_y, gy = 0; cell_y < source.height() - border_y && count < 512; cell_y += sy, ++gy) {
        for (int cell_x = border_x, gx = 0; cell_x < source.width() - border_x && count < 512; cell_x += sx, ++gx) {
            // Deterministic in-cell jitter prevents a regular sampling lattice from
            // repeatedly landing on the same phase of fine repetitive scene texture.
            const int x = std::min(source.width() - border_x - 1, cell_x + (gx * 17 + gy * 11) % sx);
            const int y = std::min(source.height() - border_y - 1, cell_y + (gx * 7 + gy * 19) % sy);
            const auto value = census(source, x, y);
            if (std::popcount(value.mask) < 4) continue;
            anchors[count++] = {x, y, value};
        }
    }
    Alignment result;
    result.anchors = count;
    if (count < 64) return result;
    constexpr int side = kCoarseSearch * 2 + 1;
    std::array<double, side * side> costs{};
    double best = std::numeric_limits<double>::infinity();
    for (int dy = center_y - radius; dy <= center_y + radius; ++dy) {
        for (int dx = center_x - radius; dx <= center_x + radius; ++dx) {
            double sum = 0;
            for (int i = 0; i < count; ++i) {
                const auto& a = anchors[i];
                const auto value = census(target, a.x + dx, a.y + dy);
                const unsigned common = value.mask & a.value.mask;
                const int usable = std::popcount(common);
                sum += usable < 3 ? 0.5 :
                    static_cast<double>(std::popcount((value.bits ^ a.value.bits) & common)) / usable;
            }
            const double cost = sum / count;
            costs[(dy - center_y + radius) * side + dx - center_x + radius] = cost;
            if (cost < best) { best = cost; result.x = dx; result.y = dy; }
        }
    }
    result.cost = best;
    double runner = std::numeric_limits<double>::infinity();
    for (int dy = center_y - radius; dy <= center_y + radius; ++dy) {
        for (int dx = center_x - radius; dx <= center_x + radius; ++dx) {
            if (std::abs(dx - result.x) <= 1 && std::abs(dy - result.y) <= 1) continue;
            runner = std::min(runner, costs[(dy - center_y + radius) * side + dx - center_x + radius]);
        }
    }
    result.margin = runner - best;
    if (proposals) {
        // Non-maximum suppression retains distinct coarse basins, including a
        // fifth sentinel so a bounded candidate budget cannot conceal ambiguity.
        for (std::size_t k = 0; k < proposals->size(); ++k) {
            Alignment candidate;
            candidate.cost = std::numeric_limits<double>::infinity();
            candidate.anchors = count;
            for (int dy = center_y - radius; dy <= center_y + radius; ++dy) {
                for (int dx = center_x - radius; dx <= center_x + radius; ++dx) {
                    bool separated = true;
                    for (std::size_t j = 0; j < k; ++j) {
                        separated = separated && (std::abs(dx - (*proposals)[j].x) > 2 ||
                                                  std::abs(dy - (*proposals)[j].y) > 2);
                    }
                    const double cost = costs[(dy - center_y + radius) * side + dx - center_x + radius];
                    if (separated && cost < candidate.cost) {
                        candidate.cost = cost; candidate.x = dx; candidate.y = dy;
                    }
                }
            }
            (*proposals)[k] = candidate;
        }
    }
    // A fractional coarse-grid translation can leave a sizeable census residual.
    // Coarse levels nominate a unique interior candidate only; the unchanged
    // residual limit is mandatory on the final native-resolution evidence.
    if ((source.scale == 1 && best > 0.15) ||
        std::abs(result.x - center_x) == radius || std::abs(result.y - center_y) == radius ||
        runner < best * 1.25 + 0.01) return result;
    result.accepted = true;
    return result;
}

Alignment align_pyramid(const Image& source, const Image& target,
                        const std::uint8_t* source_quarter, const std::uint8_t* target_quarter) {
    if (!source_quarter) return align({source, 1}, {target, 1}, kSearch);
    std::array<Alignment, 5> coarse{};
    auto result = align({source, 4, source_quarter}, {target, 4, target_quarter},
                        kCoarseSearch, 0, 0, &coarse);
    result.accepted = false;
    const double band = result.cost * 1.25 + 0.01;
    if (result.anchors < 64 || std::abs(result.x) == kCoarseSearch ||
        std::abs(result.y) == kCoarseSearch || coarse[4].cost < band) {
        result.x *= 4; result.y *= 4; return result;
    }
    std::array<Alignment, 4> half{}, fine{};
    int candidates = 0, border_x = 0, border_y = 0;
    for (int i = 0; i < 4 && coarse[i].cost < band; ++i) {
        const int cx = coarse[i].x * 2, cy = coarse[i].y * 2;
        auto candidate = align({source, 2}, {target, 2}, 3, cx, cy);
        // Intermediate matching only nominates a center. Final source-resolution
        // residual, all competing basins and reverse matching decide acceptance.
        if (candidate.anchors < 64 || std::abs(candidate.x - cx) == 3 ||
            std::abs(candidate.y - cy) == 3) {
            // An unresolved in-band alternative cannot silently disappear.
            candidate.accepted = false; candidate.x *= 2; candidate.y *= 2;
            return candidate;
        }
        bool duplicate = false;
        for (int j = 0; j < candidates; ++j) {
            duplicate = duplicate || (half[j].x == candidate.x && half[j].y == candidate.y);
        }
        if (duplicate) continue;
        half[candidates++] = candidate;
        border_x = std::max(border_x, std::abs(candidate.x * 2) + 9);
        border_y = std::max(border_y, std::abs(candidate.y * 2) + 9);
    }
    if (candidates == 0) { result.x *= 4; result.y *= 4; return result; }
    int best = 0;
    for (int i = 0; i < candidates; ++i) {
        fine[i] = align({source, 1}, {target, 1}, 3, half[i].x * 2, half[i].y * 2,
                       nullptr, border_x, border_y);
        if (std::abs(fine[i].x - half[i].x * 2) == 3 ||
            std::abs(fine[i].y - half[i].y * 2) == 3) {
            // Even a nonwinning alternative may improve outside this window.
            // Do not silently prune an unresolved retained coarse hypothesis.
            fine[i].accepted = false;
            return fine[i];
        }
        if (fine[i].cost < fine[best].cost) best = i;
    }
    result = fine[best];
    double runner = result.cost + result.margin;
    for (int i = 0; i < candidates; ++i) {
        if (std::abs(fine[i].x - result.x) > 1 || std::abs(fine[i].y - result.y) > 1)
            runner = std::min(runner, fine[i].cost);
        else runner = std::min(runner, fine[i].cost + fine[i].margin);
    }
    result.margin = runner - result.cost;
    result.accepted = result.accepted && runner >= result.cost * 1.25 + 0.01;
    return result;
}

struct Sample { float x, y; };
struct Response {
    std::array<Sample, 8> knots{};
    std::array<float, 4096> gains{};
    int count = 0;
    float ratio = 1, extension = 0;
    float evaluate(float x) const {
        if (x <= knots[0].x) return x * knots[0].y / knots[0].x;
        for (int i = 1; i < count; ++i) {
            if (x <= knots[i].x) {
                const auto a = knots[i - 1], b = knots[i];
                return a.y + (x - a.x) * (b.y - a.y) / (b.x - a.x);
            }
        }
        const auto a = knots[count - 3], b = knots[count - 1];
        return b.y + (x - b.x) * (b.y - a.y) / (b.x - a.x);
    }
    float gain(float y) const {
        return gains[std::clamp(static_cast<int>(y * 4095 + 0.5f), 0, 4095)];
    }
    float support(float y) const {
        const float u = std::clamp((y * ratio - knots[count - 1].x) / extension, 0.0f, 1.0f);
        return 1 - u * u * (3 - 2 * u);
    }
};

// Independent spatial samples validate a robust, monotone luminance transfer.
// This estimates JPEG-to-JPEG response, not the unknown sensor radiance curve.
bool calibrate(const Image& base, const Image& shorter, int dx, int dy, float ratio,
               const Tables& t, Response& response, HdrStats& stats) {
    std::array<Sample, 2048> train{}, held{};
    int nt = 0, nh = 0;
    double brightness_ratio = 0;
    const int sx = std::max(1, base.width / 40), sy = std::max(1, base.height / 30);
    for (int cy = 0, gy = 0; cy < base.height && nt < 2048 && nh < 2048; cy += sy, ++gy) {
        for (int cx = 0, gx = 0; cx < base.width && nt < 2048 && nh < 2048; cx += sx, ++gx) {
            const int x = std::min(base.width - 1, cx + (gx * 17 + gy * 11) % sx);
            const int y = std::min(base.height - 1, cy + (gx * 7 + gy * 19) % sy);
            if (x + dx < 0 || x + dx >= base.width || y + dy < 0 || y + dy >= base.height) continue;
            const auto* a = base.at(x, y); const auto* b = shorter.at(x + dx, y + dy);
            const float av = luminance(a, t, 1), bv = luminance(b, t, 1);
            if (a[3] != 255 || b[3] != 255 || std::max({a[0],a[1],a[2]}) >= 235 ||
                std::max({b[0],b[1],b[2]}) >= 240 || av < 0.02f || bv < 0.01f) continue;
            const Sample sample{bv * ratio, av};
            if ((gx + gy * 3) % 4 == 0) held[nh++] = sample;
            else { train[nt++] = sample; brightness_ratio += av / bv; }
        }
    }
    stats.calibration_samples = nt + nh;
    stats.reason = HdrReason::calibration_insufficient;
    if (nt < 128 || nh < 48) return false;
    if (brightness_ratio / nt < 1.1 || brightness_ratio / nt > ratio * 4) {
        stats.reason = HdrReason::exposure_mismatch; return false;
    }
    std::sort(train.begin(), train.begin() + nt, [](Sample a, Sample b) { return a.x < b.x; });
    response.ratio = ratio;
    for (int bin = 0; bin < 8; ++bin) {
        const int begin = nt * bin / 8, end = nt * (bin + 1) / 8;
        std::array<float, 256> values{};
        for (int i = begin; i < end; ++i) values[i - begin] = train[i].y;
        const int middle = (end - begin) / 2;
        std::nth_element(values.begin(), values.begin() + middle, values.begin() + end - begin);
        const Sample knot{train[(begin + end) / 2].x, values[middle]};
        if (response.count > 0 && knot.x - response.knots[response.count - 1].x < 0.002f) continue;
        response.knots[response.count++] = knot;
    }
    stats.calibration_knots = response.count;
    if (response.count < 4) return false;
    const auto low = response.knots[0], high = response.knots[response.count - 1];
    stats.calibration_min_luma = low.y; stats.calibration_max_luma = high.y;
    if (high.y - low.y < 0.15f || high.y < 0.35f) return false;
    stats.reason = HdrReason::calibration_rejected;
    for (int i = 1; i < response.count; ++i) {
        const auto a = response.knots[i - 1], b = response.knots[i];
        const float slope = (b.y - a.y) / (b.x - a.x);
        if (slope < 0.05f || slope > 4) return false;
    }
    int inliers = 0; double squared = 0;
    for (int i = 0; i < nh; ++i) {
        const double error = response.evaluate(held[i].x) - held[i].y;
        if (std::abs(error) <= 0.025 + 0.06 * held[i].y) ++inliers;
        squared += error * error;
    }
    stats.calibration_inlier_fraction = static_cast<double>(inliers) / nh;
    stats.calibration_rmse = std::sqrt(squared / nh);
    if (stats.calibration_inlier_fraction < 0.85 || stats.calibration_rmse > 0.035) return false;
    // Beyond the observed domain, a JPEG curve is uncertain. Extrapolation is
    // limited in both domain and influence, and never invents missing detail.
    response.extension = std::min(3 * (high.x - low.x), 2 * high.x);
    for (int i = 0; i < 4096; ++i) {
        const float y = std::max(i / 4095.0f, 0.00001f);
        response.gains[i] = response.evaluate(y * ratio) / y;
    }
    return true;
}

// Context votes must include non-clipped pixels. Entirely clipped regions have no
// local temporal correspondence evidence and deliberately retain the base image.
float context_confidence(const Image& base, const Image& shorter, int cx, int cy,
                         int dx, int dy, const Response& response, const Tables& t) {
    int eligible = 0, matches = 0;
    for (int oy = -32; oy <= 32; oy += 8) {
        for (int ox = -32; ox <= 32; ox += 8) {
            const int x = cx + ox, y = cy + oy;
            if (x < 0 || y < 0 || x >= base.width || y >= base.height ||
                x + dx < 0 || y + dy < 0 || x + dx >= base.width || y + dy >= base.height) continue;
            const auto* a = base.at(x, y);
            const auto* b = shorter.at(x + dx, y + dy);
            if (a[3] != 255 || b[3] != 255 || std::max({a[0], a[1], a[2]}) >= 235 ||
                std::max({a[0], a[1], a[2]}) < 50) continue;
            ++eligible;
            bool match = true;
            const float gain = response.gain(luminance(b, t, 1));
            for (int c = 0; c < 3; ++c) {
                const float av = t.linear[a[c]], bv = t.linear[b[c]] * gain;
                match = match && std::abs(av - bv) <= 0.035f + 0.1f * av;
            }
            matches += match ? 1 : 0;
        }
    }
    return eligible >= 6 && matches >= eligible * 0.9 ? 1.0f : 0.0f;
}
}

HdrError hdr_fuse_rgba(std::uint8_t* base, std::size_t base_stride,
                      const std::uint8_t* shorter, std::size_t short_stride,
                      int width, int height, double exposure_ratio, HdrStats& stats) noexcept {
    stats = {};
    if (!base || !shorter || base == shorter || width < 64 || height < 64 ||
        static_cast<std::uint64_t>(width) * height > 16'000'000 ||
        base_stride < static_cast<std::size_t>(width) * 4 ||
        short_stride < static_cast<std::size_t>(width) * 4 ||
        base_stride > std::numeric_limits<std::size_t>::max() / height ||
        short_stride > std::numeric_limits<std::size_t>::max() / height ||
        !std::isfinite(exposure_ratio) || exposure_ratio < 1.5 || exposure_ratio > 8) {
        return HdrError::invalid_image;
    }
    static const Tables tables;
    const float ratio = static_cast<float>(exposure_ratio);
    const Image a{base, base_stride, width, height}, b{shorter, short_stride, width, height};
    using GuideStorage = std::unique_ptr<std::uint8_t, decltype(&std::free)>;
    GuideStorage guides(nullptr, &std::free);
    const std::uint8_t* a_quarter = nullptr;
    const std::uint8_t* b_quarter = nullptr;
    if (width >= 512 && height >= 512) {
        const std::size_t pixels = static_cast<std::size_t>(width / 4) * (height / 4);
        guides.reset(static_cast<std::uint8_t*>(std::malloc(pixels * 2)));
        if (!guides) return HdrError::allocation_failed;
        // Only quarter-resolution luma is stored (<=2 MB for two 16MP inputs).
        // Half/full-resolution refinement reads bounded sparse neighborhoods.
        const Guide ga{a, 4}, gb{b, 4};
        for (int y = 0; y < height / 4; ++y) {
            for (int x = 0; x < width / 4; ++x) {
                const auto i = static_cast<std::size_t>(y) * (width / 4) + x;
                guides.get()[i] = static_cast<std::uint8_t>(ga.at(x, y));
                guides.get()[pixels + i] = static_cast<std::uint8_t>(gb.at(x, y));
            }
        }
        a_quarter = guides.get(); b_quarter = guides.get() + pixels;
    }
    const auto forward = align_pyramid(a, b, a_quarter, b_quarter);
    stats.alignment_anchors = forward.anchors;
    stats.alignment_cost = forward.cost;
    stats.alignment_margin = forward.margin;
    // On rejection these are the best candidate only, never an accepted transform.
    stats.shift_x = forward.x;
    stats.shift_y = forward.y;
    if (!forward.accepted) return HdrError::none;
    const auto reverse = align_pyramid(b, a, b_quarter, a_quarter);
    if (!reverse.accepted || std::abs(forward.x + reverse.x) > 1 ||
        std::abs(forward.y + reverse.y) > 1) {
        stats.reason = HdrReason::inconsistent_alignment;
        return HdrError::none;
    }
    Response response;
    if (!calibrate(a, b, forward.x, forward.y, ratio, tables, response, stats)) return HdrError::none;
    const int columns = (width + kGrid - 1) / kGrid + 1;
    const int rows = (height + kGrid - 1) / kGrid + 1;
    using Storage = std::unique_ptr<float, decltype(&std::free)>;
    Storage confidence(static_cast<float*>(std::malloc(columns * rows * sizeof(float))), &std::free);
    if (!confidence) return HdrError::allocation_failed;
    for (int gy = 0; gy < rows; ++gy) {
        for (int gx = 0; gx < columns; ++gx) {
            confidence.get()[gy * columns + gx] = context_confidence(
                a, b, gx * kGrid, gy * kGrid, forward.x, forward.y, response, tables);
        }
    }
    // Confidence is computed before any edits, so image updates cannot affect later decisions.
    for (int y = 0; y < height; ++y) {
        const int by = y + forward.y;
        if (by < 0 || by >= height) continue;
        const int gy = y / kGrid;
        const float fy = (y % kGrid) / static_cast<float>(kGrid);
        for (int x = 0; x < width; ++x) {
            auto* p = base + y * base_stride + x * 4;
            if (p[3] != 255 || std::max({p[0], p[1], p[2]}) < 235) continue;
            const int bx = x + forward.x;
            if (bx < 0 || bx >= width) continue;
            const auto* q = b.at(bx, by);
            if (q[3] != 255 || std::max({q[0], q[1], q[2]}) >= 250) continue;
            const int gx = x / kGrid;
            const float fx = (x % kGrid) / static_cast<float>(kGrid);
            const auto* c = confidence.get() + gy * columns + gx;
            const float trust = (c[0] * (1 - fx) + c[1] * fx) * (1 - fy) +
                                (c[columns] * (1 - fx) + c[columns + 1] * fx) * fy;
            if (trust <= 0) continue;
            const float short_luma = luminance(q, tables, 1);
            const float support = response.support(short_luma);
            if (support <= 0) continue;
            const float gain = response.gain(short_luma);
            float original[3], radiance[3];
            bool compatible = true;
            for (int channel = 0; channel < 3; ++channel) {
                original[channel] = tables.linear[p[channel]];
                radiance[channel] = tables.linear[q[channel]] * gain;
                compatible = compatible && std::abs(std::min(radiance[channel], 1.0f) -
                    original[channel]) <= 0.035f + original[channel] * 0.1f;
            }
            if (!compatible) continue;
            const float source_max = std::max({radiance[0], radiance[1], radiance[2]});
            const float base_max = std::max({original[0], original[1], original[2]});
            if (source_max < 0.82f) continue;
            // Monotone shoulder has unit slope at the knee and asymptotically approaches 1.
            // One scalar preserves the short frame's linear RGB ratios; no local sharpening.
            const float above = source_max - 0.75f;
            const float mapped = 0.75f + 0.25f * above / (above + 0.25f);
            const float scale = mapped / source_max;
            const float u = std::clamp((base_max - 0.82f) / 0.18f, 0.0f, 1.0f);
            float weight = u * u * (3 - 2 * u) * trust * support;
            float maximum_delta = 0;
            for (int channel = 0; channel < 3; ++channel) {
                maximum_delta = std::max(maximum_delta,
                    std::abs(radiance[channel] * scale - original[channel]));
            }
            // Bound the whole RGB blend, retaining one scalar weight for color.
            if (maximum_delta > 0.12f) weight = std::min(weight, 0.12f / maximum_delta);
            bool changed = false;
            for (int channel = 0; channel < 3; ++channel) {
                const float value = original[channel] * (1 - weight) + radiance[channel] * scale * weight;
                const auto encoded = tables.encoded[std::clamp(
                    static_cast<int>(value * 65535 + 0.5f), 0, 65535)];
                changed = changed || encoded != p[channel];
                p[channel] = encoded;
            }
            stats.changed += changed ? 1 : 0;
        }
    }
    stats.accepted = stats.changed > 0;
    stats.reason = stats.accepted ? HdrReason::aligned : HdrReason::no_highlight_evidence;
    return HdrError::none;
}
}
