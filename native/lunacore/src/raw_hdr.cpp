#include "lunacore/raw_hdr.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace lunacore {
namespace {

constexpr int kSearch = 12;  // RAW pixels, always even to preserve Bayer phase.
constexpr int kBorder = kSearch + 4;

struct Frame {
    RawFrame raw;
    double ratio = 1;
};

double normalized(const Frame& f, int p, int c) {
    const auto& m = f.raw.metadata;
    return (f.raw.pixels[p] - m.black[c]) / (m.white - m.black[c]);
}

double signal(const Frame& f, int p, int c) {
    return normalized(f, p, c) / f.ratio;
}

double variance(const Frame& f, int p, int c) {
    const auto& m = f.raw.metadata;
    const double range = m.white - m.black[c];
    // Quantization is independent of Android's normalized shot/read-noise model.
    return std::max(1e-12, (m.slope[c] * std::max(0.0, normalized(f, p, c)) +
        m.intercept[c] + 1.0 / (12 * range * range)) / (f.ratio * f.ratio));
}

bool usable(const Frame& f, int p, int c) {
    const auto& m = f.raw.metadata;
    const double margin = std::max(4.0, (m.white - m.black[c]) * 0.005);
    // Black is an offset, not a validity floor: read noise can cross it in a valid
    // short exposure. Reject sensor-code clipping, not signed dark measurements.
    return f.raw.pixels[p] > 0 && f.raw.pixels[p] < m.white - margin;
}

double guide(const Frame& f, int p, int width) {
    double sum = 0;
    for (int c = 0; c < 4; ++c) {
        const int q = p + c / 2 * width + c % 2;
        if (!usable(f, q, c)) return std::numeric_limits<double>::quiet_NaN();
        sum += signal(f, q, c);
    }
    return sum * 0.25;
}

struct Alignment { int x = 0; int y = 0; bool valid = false; };

Alignment align(const Frame& base, const Frame& candidate, int width,
                const std::array<int, 4>& roi) {
    std::array<double, 169> costs{};
    double best_cost = std::numeric_limits<double>::infinity();
    Alignment best;
    int index = 0;
    const int step = std::max(2, ((std::min(roi[2] - roi[0], roi[3] - roi[1]) / 32) / 2) * 2);
    const int left = (roi[0] + kBorder + 1) & ~1;
    const int top = (roi[1] + kBorder + 1) & ~1;
    for (int dy = -kSearch; dy <= kSearch; dy += 2) {
        for (int dx = -kSearch; dx <= kSearch; dx += 2) {
            double sum = 0;
            int count = 0;
            for (int y = top; y + kBorder < roi[3]; y += step) {
                for (int x = left; x + kBorder < roi[2]; x += step) {
                    const double a = guide(base, y * width + x, width);
                    const double b = guide(candidate, (y + dy) * width + x + dx, width);
                    if (!std::isfinite(a) || !std::isfinite(b)) continue;
                    const double d = a - b;
                    sum += std::min(d * d, 0.0004);
                    ++count;
                }
            }
            const double cost = count >= 64 ? sum / count : std::numeric_limits<double>::infinity();
            costs[index++] = cost;
            if (cost < best_cost) { best_cost = cost; best.x = dx; best.y = dy; }
        }
    }
    double second = std::numeric_limits<double>::infinity();
    index = 0;
    for (int dy = -kSearch; dy <= kSearch; dy += 2) {
        for (int dx = -kSearch; dx <= kSearch; dx += 2) {
            if (dx != best.x || dy != best.y) second = std::min(second, costs[index]);
            ++index;
        }
    }
    best.valid = std::isfinite(best_cost) && best_cost < 0.0001 &&
        second > best_cost * 1.15 + 1e-9 &&
        std::abs(best.x) < kSearch && std::abs(best.y) < kSearch;
    return best;
}

double confidence(const Frame& base, const Frame& candidate, int p, int q, int width) {
    const std::array<int, 5> offsets{0, -2, 2, -2 * width, 2 * width};
    double worst = 0;
    // One confidence for the entire Bayer cell prevents colored motion seams.
    // Requiring unclipped context also prevents long-exposure highlight leakage.
    for (int c = 0; c < 4; ++c) {
        const int cp = c / 2 * width + c % 2;
        double coherent = 0;
        double total_variance = 0;
        for (int offset : offsets) {
            const int bp = p + cp + offset;
            const int cq = q + cp + offset;
            if (!usable(base, bp, c) || !usable(candidate, cq, c)) return 0;
            const double d = signal(base, bp, c) - signal(candidate, cq, c);
            const double v = variance(base, bp, c) + variance(candidate, cq, c);
            worst = std::max(worst, d * d / v);
            coherent += d;
            total_variance += v;
        }
        if (coherent * coherent > 16 * total_variance) return 0;
    }
    return std::max(0.0, 1.0 - worst / 25.0);
}

std::uint32_t encode(double value) {
    return static_cast<std::uint32_t>(std::lround(std::clamp(value, 0.0, 1.0) * 65535));
}

}  // namespace

bool fuse_raw_hdr(const std::array<RawFrame, 3>& input, int width, int height,
                  std::uint32_t* output, RawHdrStats& stats, std::array<int, 4> roi) {
    if (!output || width < 64 || height < 64 || width % 2 || height % 2 ||
        static_cast<std::uint64_t>(width) * height > 16000000) return false;
    int reference = 0;
    for (int f = 0; f < 3; ++f) {
        const auto& m = input[f].metadata;
        if (!input[f].pixels || reinterpret_cast<const void*>(output) == input[f].pixels ||
            !std::isfinite(m.white) || m.white < 64 || m.white > 65535 ||
            !std::isfinite(m.exposure) || m.exposure < 1.0 / 64 || m.exposure > 64) return false;
        for (int c = 0; c < 4; ++c) {
            if (!std::isfinite(m.black[c]) || m.black[c] < 0 || m.white - m.black[c] < 32 ||
                !std::isfinite(m.slope[c]) || !std::isfinite(m.intercept[c]) ||
                m.slope[c] < 0 || m.slope[c] > 0.1 || m.intercept[c] < 0 ||
                m.intercept[c] > 0.1 || (m.slope[c] == 0 && m.intercept[c] == 0)) return false;
        }
        if (m.exposure < input[reference].metadata.exposure) reference = f;
    }
    if (roi == std::array<int, 4>{}) roi = {0, 0, width, height};
    if (roi[0] < 0 || roi[1] < 0 || roi[2] > width || roi[3] > height ||
        roi[2] - roi[0] < 64 || roi[3] - roi[1] < 64) return false;
    std::array<Frame, 3> frames{};
    for (int f = 0; f < 3; ++f) {
        frames[f] = {input[f], input[f].metadata.exposure / input[reference].metadata.exposure};
        if (frames[f].ratio > 32) return false;
    }
    stats = {};
    stats.reference = reference;
    stats.accepted_mask = 1 << reference;
    const auto& base = frames[reference];
    for (int f = 0; f < 3; ++f) {
        if (f == reference) continue;
        const auto alignment = align(base, frames[f], width, roi);
        if (!alignment.valid) continue;
        stats.shifts[f * 2] = alignment.x;
        stats.shifts[f * 2 + 1] = alignment.y;
        stats.accepted_mask |= 1 << f;
    }
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const int c = (y % 2) * 2 + x % 2;
            output[y * width + x] = encode(normalized(base, y * width + x, c));
        }
    }
    const int left = (roi[0] + 3) & ~1;
    const int top = (roi[1] + 3) & ~1;
    for (int y = top; y + 3 < roi[3]; y += 2) {
        for (int x = left; x + 3 < roi[2]; x += 2) {
            const int p = y * width + x;
            std::array<double, 3> trust{};
            std::array<int, 3> positions{};
            for (int f = 0; f < 3; ++f) {
                if (f == reference || !(stats.accepted_mask & (1 << f))) continue;
                const int xx = x + stats.shifts[f * 2];
                const int yy = y + stats.shifts[f * 2 + 1];
                if (xx < roi[0] + 2 || yy < roi[1] + 2 ||
                    xx + 3 >= roi[2] || yy + 3 >= roi[3]) continue;
                positions[f] = yy * width + xx;
                trust[f] = confidence(base, frames[f], p, positions[f], width);
            }
            if (trust[0] + trust[1] + trust[2] <= 0) { ++stats.rejected_cells; continue; }
            for (int c = 0; c < 4; ++c) {
                const int cp = c / 2 * width + c % 2;
                const double base_weight = 1 / variance(base, p + cp, c);
                double sum = base_weight * signal(base, p + cp, c);
                double weight_sum = base_weight;
                for (int f = 0; f < 3; ++f) {
                    if (trust[f] <= 0) continue;
                    const int q = positions[f] + cp;
                    // Exposure-normalized variance already prices the short frame's noise.
                    // A reference-weight floor would throw away the long exposure's SNR;
                    // registration, clipping checks and cell trust still reject unsafe samples.
                    const double w = trust[f] / variance(frames[f], q, c);
                    sum += w * signal(frames[f], q, c);
                    weight_sum += w;
                }
                output[p + cp] = encode(sum / weight_sum);
                ++stats.merged_pixels;
            }
        }
    }
    return true;
}

}  // namespace lunacore
