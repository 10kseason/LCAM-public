#include "lunacore/texture_guard.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <memory>
#include <new>

namespace lunacore {
namespace {
struct Pixel {
    std::uint8_t r, g, b, a;
    std::int16_t y4, co2, cg4;
};

void read_row(const std::uint8_t* input, Pixel* output, std::size_t width) {
    for (std::size_t x = 0; x < width; ++x) {
        const auto* p = input + 4 * x;
        const int y4 = p[0] + 2 * p[1] + p[2];
        output[x] = {p[0], p[1], p[2], p[3], static_cast<std::int16_t>(y4),
                     static_cast<std::int16_t>(p[0] - p[2]),
                     static_cast<std::int16_t>(4 * p[1] - y4)};
    }
}

bool filter(const Pixel& p, const Pixel* const above, const Pixel* const center,
            const Pixel* const below, std::size_t x, std::uint8_t* output) {
    if (p.a != 255 || p.y4 < 64 || p.y4 > 956) return false;
    const Pixel* a[] = {center + x - 1, above + x, above + x - 1, above + x + 1};
    const Pixel* z[] = {center + x + 1, below + x, below + x + 1, below + x - 1};
    int min_y = p.y4, max_y = p.y4;
    int min_co = 2048, max_co = -2048, min_cg = 4096, max_cg = -4096;
    int sum_co = 0, sum_cg = 0, votes = 0;
    for (int direction = 0; direction != 4; ++direction) {
        const auto& left = *a[direction];
        const auto& right = *z[direction];
        if (left.a != 255 || right.a != 255) return false;
        min_y = std::min({min_y, static_cast<int>(left.y4), static_cast<int>(right.y4)});
        max_y = std::max({max_y, static_cast<int>(left.y4), static_cast<int>(right.y4)});
        if (max_y - min_y > 40) return false;
        if (std::abs(left.y4 - right.y4) <= 24 &&
            std::abs(left.co2 - right.co2) <= 12 &&
            std::abs(left.cg4 - right.cg4) <= 24) {
            const int pair_co = left.co2 + right.co2;
            const int pair_cg = left.cg4 + right.cg4;
            min_co = std::min(min_co, pair_co);
            max_co = std::max(max_co, pair_co);
            min_cg = std::min(min_cg, pair_cg);
            max_cg = std::max(max_cg, pair_cg);
            sum_co += pair_co;
            sum_cg += pair_cg;
            ++votes;
        }
    }
    if (votes < 3 || max_co - min_co > 16 || max_cg - min_cg > 24) return false;
    // Powers-of-two fixed-point sums are exact. Preserve Kotlin's float division and
    // multiplication order at the only non-exact operations, including ties toward +infinity.
    const float residual_co = (static_cast<float>(sum_co) / 4.0f) / votes - p.co2 / 2.0f;
    const float residual_cg = (static_cast<float>(sum_cg) / 8.0f) / votes - p.cg4 / 4.0f;
    if (std::abs(residual_co) > 12.0f || std::abs(residual_cg) > 12.0f) return false;
    const int dc = std::clamp(static_cast<int>(std::floor(
        static_cast<double>(residual_co * 0.35f) + 0.5)), -2, 2);
    const int dg = std::clamp(static_cast<int>(std::floor(
        static_cast<double>(residual_cg * 0.35f) + 0.5)), -1, 1);
    const int r = p.r + dc - dg, g = p.g + dg, b = p.b - dc - dg;
    if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) return false;
    if (r == p.r && g == p.g && b == p.b) return false;
    output[0] = static_cast<std::uint8_t>(r);
    output[1] = static_cast<std::uint8_t>(g);
    output[2] = static_cast<std::uint8_t>(b);
    return true;
}
}

TextureGuardError texture_guard_rgba(std::uint8_t* pixels, std::size_t width,
                                   std::size_t height, std::size_t stride,
                                   std::uint64_t& changed) noexcept {
    changed = 0;
    constexpr std::size_t max_pixels = 16'000'000;
    if (pixels == nullptr || width == 0 || height == 0 || width > max_pixels ||
        height > max_pixels / width || stride < width * 4 ||
        stride > std::numeric_limits<std::size_t>::max() / height) {
        return TextureGuardError::invalid_image;
    }
    if (width < 3 || height < 3) return TextureGuardError::none;
    std::unique_ptr<Pixel[]> rows(new (std::nothrow) Pixel[3 * width]);
    if (!rows) return TextureGuardError::allocation_failed;
    Pixel* above = rows.get();
    Pixel* center = above + width;
    Pixel* below = center + width;
    read_row(pixels, above, width);
    read_row(pixels + stride, center, width);
    read_row(pixels + stride * 2, below, width);
    for (std::size_t y = 1; y + 1 < height; ++y) {
        auto* output = pixels + y * stride;
        for (std::size_t x = 1; x + 1 < width; ++x) {
            changed += filter(center[x], above, center, below, x, output + 4 * x) ? 1 : 0;
        }
        auto* recycled = above;
        above = center;
        center = below;
        below = recycled;
        if (y + 2 < height) read_row(pixels + (y + 2) * stride, below, width);
    }
    return TextureGuardError::none;
}
}
