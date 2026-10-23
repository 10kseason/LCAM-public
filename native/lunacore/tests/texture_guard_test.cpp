#include "lunacore/texture_guard.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <limits>
#include <random>
#include <stdexcept>
#include <vector>

namespace {
void check(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

// Deliberately uses the Kotlin implementation's floating-point formulas, not
// the optimized core's cached integer representation or streaming data flow.
std::array<std::uint8_t, 4> reference(const std::uint8_t* p, std::size_t stride) {
    auto y = [](const std::uint8_t* v) { return (v[0] + 2 * v[1] + v[2]) / 4.0f; };
    auto co = [](const std::uint8_t* v) { return (v[0] - v[2]) / 2.0f; };
    std::array<std::uint8_t, 4> unchanged{p[0], p[1], p[2], p[3]};
    const float center_y = y(p), center_co = co(p), center_cg = p[1] - center_y;
    if (p[3] != 255 || center_y < 16 || center_y > 239) return unchanged;
    float min_y = center_y, max_y = center_y;
    float min_co = INFINITY, max_co = -INFINITY, min_cg = INFINITY, max_cg = -INFINITY;
    float sum_co = 0, sum_cg = 0;
    int votes = 0;
    const std::uint8_t* a[] = {p - 4, p - stride, p - stride - 4, p - stride + 4};
    const std::uint8_t* z[] = {p + 4, p + stride, p + stride + 4, p + stride - 4};
    for (int d = 0; d != 4; ++d) {
        if (a[d][3] != 255 || z[d][3] != 255) return unchanged;
        const float ay = y(a[d]), zy = y(z[d]);
        min_y = std::min({min_y, ay, zy});
        max_y = std::max({max_y, ay, zy});
        if (max_y - min_y > 10) return unchanged;
        const float ac = co(a[d]), zc = co(z[d]);
        const float ag = a[d][1] - ay, zg = z[d][1] - zy;
        if (std::abs(ay - zy) <= 6 && std::abs(ac - zc) <= 6 && std::abs(ag - zg) <= 6) {
            const float pair_co = (ac + zc) / 2, pair_cg = (ag + zg) / 2;
            min_co = std::min(min_co, pair_co);
            max_co = std::max(max_co, pair_co);
            min_cg = std::min(min_cg, pair_cg);
            max_cg = std::max(max_cg, pair_cg);
            sum_co += pair_co;
            sum_cg += pair_cg;
            ++votes;
        }
    }
    if (votes < 3 || max_co - min_co > 4 || max_cg - min_cg > 3) return unchanged;
    const float rc = sum_co / votes - center_co, rg = sum_cg / votes - center_cg;
    if (std::abs(rc) > 12 || std::abs(rg) > 12) return unchanged;
    const int dc = std::clamp(static_cast<int>(std::floor(static_cast<double>(rc * 0.35f) + 0.5)), -2, 2);
    const int dg = std::clamp(static_cast<int>(std::floor(static_cast<double>(rg * 0.35f) + 0.5)), -1, 1);
    const int r = p[0] + dc - dg, g = p[1] + dg, b = p[2] - dc - dg;
    if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) return unchanged;
    return {static_cast<std::uint8_t>(r), static_cast<std::uint8_t>(g),
            static_cast<std::uint8_t>(b), p[3]};
}

std::uint64_t verify(std::vector<std::uint8_t> input, std::size_t w, std::size_t h, std::size_t stride) {
    auto expected = input;
    std::uint64_t expected_changed = 0;
    for (std::size_t y = 1; y + 1 < h; ++y) {
        for (std::size_t x = 1; x + 1 < w; ++x) {
            const std::size_t index = y * stride + x * 4;
            const auto p = reference(input.data() + index, stride);
            const bool different = !std::equal(p.begin(), p.end(), input.begin() + index);
            expected_changed += different ? 1 : 0;
            std::copy(p.begin(), p.end(), expected.begin() + index);
            check(input[index] + 2 * input[index + 1] + input[index + 2] ==
                  p[0] + 2 * p[1] + p[2], "luma proxy changed");
            for (int c = 0; c < 3; ++c) {
                check(std::abs(static_cast<int>(p[c]) - input[index + c]) <= 3, "channel bound");
            }
        }
    }
    std::uint64_t changed = 0;
    check(lunacore::texture_guard_rgba(input.data(), w, h, stride, changed) ==
          lunacore::TextureGuardError::none, "core error");
    check(input == expected, "bit-exact reference mismatch, padding or border overwritten");
    check(changed == expected_changed, "changed count mismatch");
    return changed;
}
}

int main() {
    try {
        std::mt19937 random(89071);
        std::uint64_t changes = 0;
        std::size_t examined = 0;
        for (int fixture = 0; fixture < 256; ++fixture) {
            const std::size_t w = 67, h = 61, stride = 4 * w + fixture % 13;
            std::vector<std::uint8_t> data(stride * h, 173);
            const int base = fixture;
            for (std::size_t y = 0; y < h; ++y) {
                for (std::size_t x = 0; x < w; ++x) {
                    const auto i = y * stride + 4 * x;
                    const int noise = fixture % 5 == 0 ? 255 : 13;
                    for (int c = 0; c < 3; ++c) {
                        data[i + c] = static_cast<std::uint8_t>(std::clamp(
                            base + static_cast<int>(random() % (2 * noise + 1)) - noise, 0, 255));
                    }
                    data[i + 3] = random() % 101 == 0 ? static_cast<std::uint8_t>(random()) : 255;
                }
            }
            changes += verify(data, w, h, stride);
            examined += (w - 2) * (h - 2);
        }
        // Low-amplitude constant-luma chroma noise exercises the accepted path densely.
        std::vector<std::uint8_t> data(4 * 128 * 128);
        for (std::size_t i = 0; i < data.size(); i += 4) {
            const int co = static_cast<int>(random() % 13) - 6;
            const int cg = static_cast<int>(random() % 7) - 3;
            data[i] = static_cast<std::uint8_t>(128 + co - cg);
            data[i + 1] = static_cast<std::uint8_t>(128 + cg);
            data[i + 2] = static_cast<std::uint8_t>(128 - co - cg);
            data[i + 3] = 255;
        }
        changes += verify(data, 128, 128, 512);
        check(changes > 1000, "fixtures fail to exercise correction path");
        check(verify(std::vector<std::uint8_t>(20, 89), 1, 5, 4) == 0, "tiny image");
        std::uint64_t changed = 77;
        check(lunacore::texture_guard_rgba(nullptr, 1, 1, 4, changed) ==
              lunacore::TextureGuardError::invalid_image && changed == 0, "null validation");
        check(lunacore::texture_guard_rgba(data.data(), 128, 128, 511, changed) ==
              lunacore::TextureGuardError::invalid_image, "stride validation");
        check(lunacore::texture_guard_rgba(data.data(), 1, 2,
              std::numeric_limits<std::size_t>::max(), changed) ==
              lunacore::TextureGuardError::invalid_image, "overflow validation");
        std::cout << "Texture Guard exact parity: " << examined + 126 * 126
                  << " pixels, " << changes << " changes; bounds, alpha, padding validated\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
