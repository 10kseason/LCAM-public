#include "lunacore/raw_fusion.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>

namespace lunacore {
namespace {

constexpr int kSearch = 8;  // Bayer cells: shifts always preserve the CFA phase.
constexpr double kVarianceFloor = 1e-10;

struct WorkingFrame : RawFrame {
    std::array<double, 4> scale{};
    std::array<double, 4> noise_slope{};
    std::array<double, 4> noise_intercept{};
};

double signal(const WorkingFrame& f, int p, int c) {
    return (f.pixels[p] - f.metadata.black[c]) * f.scale[c];
}

double variance(const WorkingFrame& f, double s, int c) {
    return std::max(kVarianceFloor, f.noise_slope[c] * std::max(0.0, s) + f.noise_intercept[c]);
}

bool usable(const RawFrame& f, int p, int c) {
    const auto& m = f.metadata;
    const double margin = std::max(4.0, (m.white - m.black[c]) * 0.005);
    return f.pixels[p] > m.black[c] + 1 && f.pixels[p] < m.white - margin;
}

struct Guide {
    const float* values;
    int width;
    int height;
};

// Sample small boxes at two spatial scales. Missing/saturated boxes cannot vote.
double box(const Guide& g, int x, int y, int radius) {
    double sum = 0;
    for (int j = -radius; j <= radius; ++j) {
        for (int i = -radius; i <= radius; ++i) {
            const float v = g.values[(y + j) * g.width + x + i];
            if (!std::isfinite(v)) return std::numeric_limits<double>::quiet_NaN();
            sum += v;
        }
    }
    const int side = radius * 2 + 1;
    return sum / (side * side);
}

double cost(const Guide& a, const Guide& b, int dx, int dy, int radius) {
    const int border = kSearch + 3;
    const int step = std::max(2, std::min(a.width, a.height) / 28);
    double sum = 0;
    int count = 0;
    for (int y = border; y < a.height - border; y += step) {
        for (int x = border; x < a.width - border; x += step) {
            const double va = box(a, x, y, radius);
            const double vb = box(b, x + dx, y + dy, radius);
            if (!std::isfinite(va) || !std::isfinite(vb)) continue;
            const double d = va - vb;
            // Robust loss limits local movers' influence on global alignment.
            sum += std::min(d * d, 0.0025);
            ++count;
        }
    }
    return count >= 32 ? sum / count : std::numeric_limits<double>::infinity();
}

struct Alignment {
    int x = 0;
    int y = 0;
    bool valid = false;
};

Alignment align(const Guide& a, const Guide& b) {
    Alignment best;
    double best_cost = std::numeric_limits<double>::infinity();
    std::array<double, 81> coarse_costs{};
    int coarse_index = 0;
    // Coarse box-smoothed exhaustive translations, then full-resolution guide.
    for (int dy = -kSearch; dy <= kSearch; dy += 2) {
        for (int dx = -kSearch; dx <= kSearch; dx += 2) {
            const double c = cost(a, b, dx, dy, 1);
            coarse_costs[coarse_index++] = c;
            if (c < best_cost) { best_cost = c; best.x = dx; best.y = dy; }
        }
    }
    const int coarse_x = best.x;
    const int coarse_y = best.y;
    double distant_cost = std::numeric_limits<double>::infinity();
    coarse_index = 0;
    for (int dy = -kSearch; dy <= kSearch; dy += 2) {
        for (int dx = -kSearch; dx <= kSearch; dx += 2) {
            if (std::abs(dx - coarse_x) > 2 || std::abs(dy - coarse_y) > 2)
                distant_cost = std::min(distant_cost, coarse_costs[coarse_index]);
            ++coarse_index;
        }
    }
    const bool unique_coarse = distant_cost > best_cost * 1.1 + 1e-8;
    best_cost = std::numeric_limits<double>::infinity();
    double second = best_cost;
    for (int dy = std::max(-kSearch, coarse_y - 2);
         dy <= std::min(kSearch, coarse_y + 2); ++dy) {
        for (int dx = std::max(-kSearch, coarse_x - 2);
             dx <= std::min(kSearch, coarse_x + 2); ++dx) {
            const double c = cost(a, b, dx, dy, 0);
            if (c < best_cost) {
                second = best_cost; best_cost = c; best.x = dx; best.y = dy;
            } else if (c < second) { second = c; }
        }
    }
    // Ambiguous flat/repeated patterns and search-boundary optima fall back.
    best.valid = unique_coarse && std::isfinite(best_cost) && best_cost < 0.0004 &&
                 second > best_cost * 1.15 + 1e-8 &&
                 std::abs(best.x) < kSearch && std::abs(best.y) < kSearch;
    return best;
}

double confidence(const WorkingFrame& base, const WorkingFrame& candidate, int p, int q,
                  int width) {
    const std::array<int, 5> offsets{0, -2, 2, -2 * width, 2 * width};
    double worst = 0;
    // All CFA channels vote together: no colored ghosts from channel-wise masks.
    for (int c = 0; c < 4; ++c) {
        const int cp = (c / 2) * width + c % 2;
        double signed_sum = 0;
        double variance_sum = 0;
        for (int offset : offsets) {
            const int bp = p + cp + offset;
            const int cq = q + cp + offset;
            if (!usable(base, bp, c) || !usable(candidate, cq, c)) return 0;
            const double a = signal(base, bp, c);
            const double b = signal(candidate, cq, c);
            const double v = variance(base, a, c) +
                             variance(candidate, b, c);
            const double d = a - b;
            if (d * d > 16 * v) return 0;
            worst = std::max(worst, d * d / v);
            signed_sum += d;
            variance_sum += v;
        }
        // Coherent local mismatch is motion/exposure error, not random noise.
        if (signed_sum * signed_sum > 9 * variance_sum) return 0;
        // Detail recurrence at radii 2 and 4 RAW pixels, same CFA plane only.
        for (int radius : {2, 4}) {
            for (int stride : {1, width}) {
                const int d = radius * stride;
                const double a0 = signal(base, p + cp - d, c);
                const double a1 = signal(base, p + cp + d, c);
                const double b0 = signal(candidate, q + cp - d, c);
                const double b1 = signal(candidate, q + cp + d, c);
                const double va = variance(base, a0, c) +
                                  variance(base, a1, c);
                const double vb = variance(candidate, b0, c) +
                                  variance(candidate, b1, c);
                const double ga = a1 - a0;
                const double gb = b1 - b0;
                if (ga * ga > 16 * va && gb * gb > 16 * vb && ga * gb < 0) return 0;
                const double error = ga - gb;
                if (error * error > 16 * (va + vb)) return 0;
            }
        }
    }
    // Continuous taper within the rejection boundary reduces mask seams.
    return std::max(0.0, 1.0 - worst / 16.0);
}

}  // namespace

bool fuse_raw(const std::array<RawFrame, 3>& input, int width, int height,
              std::uint16_t* output, RawFusionStats& stats, std::array<int, 4> active_area) {
    if (!output || width < 64 || height < 64 || width % 2 || height % 2 ||
        static_cast<std::uint64_t>(width) * height > 16000000) return false;
    for (const auto& f : input) {
        const auto& m = f.metadata;
        if (!f.pixels || output == f.pixels || !std::isfinite(m.white) ||
            m.white > 65535 || m.white < 64 || !std::isfinite(m.exposure) ||
            m.exposure < 0.5 || m.exposure > 2) return false;
        for (int c = 0; c < 4; ++c) {
            if (!std::isfinite(m.black[c]) || m.black[c] < 0 ||
                m.white - m.black[c] < 32 || !std::isfinite(m.slope[c]) ||
                !std::isfinite(m.intercept[c]) || m.slope[c] < 0 ||
                m.slope[c] > 0.1 || m.intercept[c] < 0 || m.intercept[c] > 0.1 ||
                (m.slope[c] == 0 && m.intercept[c] == 0)) return false;
        }
    }
    if (active_area == std::array<int, 4>{}) active_area = {0, 0, width, height};
    const auto [left, top, right, bottom] = active_area;
    if (left < 0 || top < 0 || right > width || bottom > height ||
        right - left < 64 || bottom - top < 64) return false;
    std::array<WorkingFrame, 3> frames{};
    for (int f = 0; f < 3; ++f) {
        frames[f].pixels = input[f].pixels;
        frames[f].metadata = input[f].metadata;
        const auto& m = input[f].metadata;
        for (int c = 0; c < 4; ++c) {
            frames[f].scale[c] = 1 / ((m.white - m.black[c]) * m.exposure);
            frames[f].noise_slope[c] = m.slope[c] / m.exposure;
            frames[f].noise_intercept[c] = m.intercept[c] / (m.exposure * m.exposure);
        }
    }
    const int gw = width / 2;
    const int gh = height / 2;
    const auto cells = static_cast<std::size_t>(gw) * gh;
    std::unique_ptr<float, decltype(&std::free)> memory(
        static_cast<float*>(std::malloc(cells * 3 * sizeof(float))), &std::free);
    if (!memory) return false;
    std::array<Guide, 3> guides{};
    std::array<double, 3> scores{};
    for (int f = 0; f < 3; ++f) {
        float* g = memory.get() + cells * f;
        guides[f] = {g, gw, gh};
        std::uint64_t valid = 0;
        for (int y = 0; y < gh; ++y) {
            for (int x = 0; x < gw; ++x) {
                const int p = 2 * y * width + 2 * x;
                double sum = 0;
                bool good = 2 * x >= left && 2 * y >= top &&
                            2 * x + 1 < right && 2 * y + 1 < bottom;
                for (int c = 0; c < 4; ++c) {
                    const int pos = p + (c / 2) * width + c % 2;
                    good = good && usable(frames[f], pos, c);
                    sum += signal(frames[f], pos, c);
                }
                g[y * gw + x] = good ? static_cast<float>(sum / 4) :
                    std::numeric_limits<float>::quiet_NaN();
                if (good) ++valid;
            }
        }
        double sharp = 0;
        int count = 0;
        for (int y = 2; y < gh - 2; y += 4) {
            for (int x = 2; x < gw - 2; x += 4) {
                const double a = box(guides[f], x - 1, y, 1);
                const double b = box(guides[f], x + 1, y, 1);
                if (std::isfinite(a) && std::isfinite(b)) {
                    sharp += std::min(std::abs(a - b), 0.1); ++count;
                }
            }
        }
        scores[f] = (count ? sharp / count : 0) * static_cast<double>(valid) / cells;
    }
    stats = {};
    // Small score differences are noise: stable earlier-frame tie-breaking.
    for (int f = 1; f < 3; ++f) {
        if (scores[f] > scores[stats.baseline] * 1.1 + 1e-6) stats.baseline = f;
    }
    const auto& base = frames[stats.baseline];
    std::memcpy(output, base.pixels, static_cast<std::size_t>(width) * height * sizeof(*output));
    std::array<Alignment, 3> alignment{};
    for (int f = 0; f < 3; ++f) {
        if (f == stats.baseline) continue;
        auto a = align(guides[stats.baseline], guides[f]);
        const auto reverse = align(guides[f], guides[stats.baseline]);
        a.valid = a.valid && reverse.valid && a.x + reverse.x == 0 && a.y + reverse.y == 0;
        alignment[f] = a;
        if (a.valid) {
            ++stats.accepted_frames;
            stats.shifts[2 * f] = a.x * 2;
            stats.shifts[2 * f + 1] = a.y * 2;
        }
    }
    double effective_sum = 0;
    for (int y = ((top + 5) / 2) * 2; y < bottom - 5; y += 2) {
        for (int x = ((left + 5) / 2) * 2; x < right - 5; x += 2) {
            const int p = y * width + x;
            std::array<int, 3> positions{};
            std::array<double, 3> reliability{};
            for (int f = 0; f < 3; ++f) {
                const auto& a = alignment[f];
                if (!a.valid) continue;
                const int qx = x + a.x * 2;
                const int qy = y + a.y * 2;
                if (qx < left + 4 || qy < top + 4 || qx + 5 >= right || qy + 5 >= bottom) continue;
                positions[f] = qy * width + qx;
                reliability[f] = confidence(base, frames[f], p, positions[f], width);
            }
            for (int c = 0; c < 4; ++c) {
                const int offset = (c / 2) * width + c % 2;
                const int pos = p + offset;
                if (!usable(base, pos, c)) continue;
                const double original = signal(base, pos, c);
                const double vb = variance(base, original, c);
                const double w0 = 1 / vb;
                double sum = original * w0;
                double weight = w0;
                double squared = w0 * w0;
                for (int f = 0; f < 3; ++f) {
                    if (reliability[f] <= 0) continue;
                    const double s = signal(frames[f], positions[f] + offset, c);
                    // Cap exposure/noise advantages: baseline always retains >=1/3 influence.
                    const double w = reliability[f] * std::min(w0,
                        1 / variance(frames[f], s, c));
                    sum += s * w; weight += w; squared += w * w;
                }
                const double neff = weight * weight / squared;
                if (neff < 1.25) continue;
                const double strength = std::min(1.0, (neff - 1) / 1.0);
                const double budget = std::min(0.01 / base.metadata.exposure, 2 * std::sqrt(vb));
                const double delta = std::clamp((sum / weight - original) * strength, -budget, budget);
                const double raw = (original + delta) * base.metadata.exposure *
                                   (base.metadata.white - base.metadata.black[c]) + base.metadata.black[c];
                const auto value = static_cast<std::uint16_t>(std::clamp(std::round(raw), 0.0, 65535.0));
                output[pos] = value;
                ++stats.merged;
                effective_sum += neff;
                if (value != base.pixels[pos]) ++stats.changed;
            }
        }
    }
    stats.rejected = static_cast<std::uint64_t>(width) * height - stats.merged;
    stats.mean_effective_samples = stats.merged ? effective_sum / stats.merged : 1;
    return true;
}

}  // namespace lunacore
