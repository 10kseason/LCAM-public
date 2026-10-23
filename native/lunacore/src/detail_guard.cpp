#include "lunacore/detail_guard.h"

#include <algorithm>
#include <array>
#include <cstdlib>
#include <limits>
#include <memory>
#include <new>

namespace lunacore {
namespace {
struct Pixel {
    std::uint8_t r, g, b, a;
    std::int16_t y4, co, cg;
};
void read_row(const std::uint8_t* input, Pixel* output, std::size_t width) {
    for (std::size_t x = 0; x < width; ++x) {
        const auto* p = input + x * 4;
        const int y = p[0] + 2 * p[1] + p[2];
        output[x] = {p[0], p[1], p[2], p[3], static_cast<std::int16_t>(y),
            static_cast<std::int16_t>(p[0] - p[2]), static_cast<std::int16_t>(4 * p[1] - y)};
    }
}
int rounded(int numerator, int denominator) {
    const int magnitude = (std::abs(numerator) + denominator / 2) / denominator;
    return numerator < 0 ? -magnitude : magnitude;
}
int filter(const std::array<Pixel*, 5>& rows, std::size_t x, std::uint8_t* output) {
    const Pixel& p = rows[2][x];
    if (p.a != 255 || p.y4 < 80 || p.y4 > 940) return 0;
    constexpr int dx[] = {-1, 0, -1, 1};
    constexpr int dy[] = {0, -1, -1, -1};
    std::array<const Pixel*, 4> a{}, b{}, outer_a{}, outer_b{};
    int lo = p.y4, hi = p.y4;
    for (int d = 0; d < 4; ++d) {
        a[d] = rows[2 - dy[d]] + x - dx[d];
        b[d] = rows[2 + dy[d]] + x + dx[d];
        outer_a[d] = rows[2 - 2 * dy[d]] + x - 2 * dx[d];
        outer_b[d] = rows[2 + 2 * dy[d]] + x + 2 * dx[d];
        if (a[d]->a != 255 || b[d]->a != 255 || outer_a[d]->a != 255 || outer_b[d]->a != 255) return 0;
        lo = std::min({lo, static_cast<int>(a[d]->y4), static_cast<int>(b[d]->y4)});
        hi = std::max({hi, static_cast<int>(a[d]->y4), static_cast<int>(b[d]->y4)});
    }
    int delta = 0;
    const bool denoise = hi - lo <= 48;
    if (denoise) {
        // Weak structured signals cannot be distinguished from noise here.
        // Preserve the entire <=5-code-value span and coherent thin lines.
        if (hi - lo <= 20 || p.y4 == lo || p.y4 == hi) return 0;
        if (hi - lo >= 8) {
            for (int d = 0; d < 4; ++d) {
                if ((std::abs(a[d]->y4 - p.y4) <= 4 && std::abs(outer_a[d]->y4 - p.y4) <= 4) ||
                    (std::abs(b[d]->y4 - p.y4) <= 4 && std::abs(outer_b[d]->y4 - p.y4) <= 4))
                    return 0;
            }
        }
        int votes = 0, sum = 0, minimum = 2048, maximum = -1;
        for (int d = 0; d < 4; ++d) {
            if (std::abs(a[d]->y4 - b[d]->y4) <= 16 &&
                std::abs(a[d]->co - b[d]->co) <= 12 && std::abs(a[d]->cg - b[d]->cg) <= 24) {
                const int pair = a[d]->y4 + b[d]->y4;
                ++votes; sum += pair;
                minimum = std::min(minimum, pair); maximum = std::max(maximum, pair);
            }
        }
        if (votes < 3 || maximum - minimum > 24) return 0;
        const int residual = sum - 2 * votes * p.y4;
        if (std::abs(residual) > 48 * votes) return 0;
        delta = std::clamp(rounded(7 * residual, 160 * votes), -2, 2);
    } else {
        int strongest = 0, detail = 0;
        for (int d = 0; d < 4; ++d) {
            const int g1 = b[d]->y4 - a[d]->y4;
            const int g2 = outer_b[d]->y4 - outer_a[d]->y4;
            const int d1 = 2 * p.y4 - a[d]->y4 - b[d]->y4;
            const int d2 = 2 * p.y4 - outer_a[d]->y4 - outer_b[d]->y4;
            const int magnitude = std::abs(g1);
            if (g1 * g2 > 0 && magnitude >= 32 && std::abs(g2) >= magnitude &&
                std::abs(g2) <= 4 * magnitude && d1 * d2 > 0 &&
                std::abs(d2) >= std::abs(d1) && std::abs(d1) >= 8 && magnitude > strongest) {
                const int nx = -dy[d], ny = dx[d];
                const auto* h1a = rows[2 + ny + dy[d]] + x + nx + dx[d];
                const auto* h1b = rows[2 + ny - dy[d]] + x + nx - dx[d];
                const auto* h2a = rows[2 - ny + dy[d]] + x - nx + dx[d];
                const auto* h2b = rows[2 - ny - dy[d]] + x - nx - dx[d];
                // A real edge should recur on parallel neighboring lines. A
                // transparent added sample disqualifies only this direction.
                if (h1a->a != 255 || h1b->a != 255 || h2a->a != 255 || h2b->a != 255 ||
                    std::abs((h1a->y4 - h1b->y4) - g1) > 8 ||
                    std::abs((h2a->y4 - h2b->y4) - g1) > 8) continue;
                strongest = magnitude; detail = d1;
            }
        }
        if (strongest == 0) return 0;
        delta = std::clamp(rounded(detail, 32), -2, 2);
        // lo-center <= 0 and hi-center >= 0: C++ truncation gives the required
        // ceil lower / floor upper bounds without changing signed rounding rules.
        delta = std::clamp(delta, (lo - p.y4) / 4, (hi - p.y4) / 4);
    }
    delta = std::clamp(delta, -std::min({p.r, p.g, p.b}), 255 - std::max({p.r, p.g, p.b}));
    if (delta == 0) return 0;
    output[0] = static_cast<std::uint8_t>(p.r + delta);
    output[1] = static_cast<std::uint8_t>(p.g + delta);
    output[2] = static_cast<std::uint8_t>(p.b + delta);
    return denoise ? 1 : 2;
}
}

DetailGuardError detail_guard_rgba(std::uint8_t* pixels, std::size_t width,
                                  std::size_t height, std::size_t stride,
                                  DetailGuardStats& stats) noexcept {
    stats = {};
    constexpr std::size_t limit = 16'000'000;
    if (!pixels || width == 0 || height == 0 || width > limit || height > limit / width ||
        stride < width * 4 || stride > std::numeric_limits<std::size_t>::max() / height)
        return DetailGuardError::invalid_image;
    if (width < 5 || height < 5) return DetailGuardError::none;
    std::unique_ptr<Pixel[]> cache(new (std::nothrow) Pixel[5 * width]);
    if (!cache) return DetailGuardError::allocation_failed;
    std::array<Pixel*, 5> rows{};
    for (std::size_t i = 0; i < 5; ++i) {
        rows[i] = cache.get() + i * width;
        read_row(pixels + i * stride, rows[i], width);
    }
    // Allocation is complete before the first write. A five-row rolling source
    // cache keeps every decision independent of earlier output modifications.
    for (std::size_t y = 2; y + 2 < height; ++y) {
        for (std::size_t x = 2; x + 2 < width; ++x) {
            const int changed = filter(rows, x, pixels + y * stride + x * 4);
            stats.denoised += changed == 1 ? 1 : 0;
            stats.sharpened += changed == 2 ? 1 : 0;
        }
        Pixel* recycled = rows[0];
        for (std::size_t i = 0; i < 4; ++i) rows[i] = rows[i + 1];
        rows[4] = recycled;
        if (y + 3 < height) read_row(pixels + (y + 3) * stride, recycled, width);
    }
    return DetailGuardError::none;
}
}
