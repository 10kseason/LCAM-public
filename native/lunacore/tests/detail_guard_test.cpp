#include "lunacore/detail_guard.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <vector>

namespace {
void check(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
struct Image {
    int width, height;
    std::size_t stride;
    std::vector<std::uint8_t> data;
    Image(int w, int h) : width(w), height(h), stride(static_cast<std::size_t>(w) * 4 + 13),
        data(stride * h, 173) {}
    std::uint8_t* at(int x, int y) { return data.data() + y * stride + x * 4; }
    const std::uint8_t* at(int x, int y) const { return data.data() + y * stride + x * 4; }
    void grey(int x, int y, int value) {
        auto* p = at(x, y);
        p[0] = p[1] = p[2] = static_cast<std::uint8_t>(value); p[3] = 255;
    }
    void fill(int value) {
        for (int y = 0; y < height; ++y) for (int x = 0; x < width; ++x) grey(x, y, value);
    }
};
std::uint32_t next(std::uint32_t& state) {
    state ^= state << 13; state ^= state >> 17; state ^= state << 5; return state;
}
lunacore::DetailGuardStats run(Image& image) {
    lunacore::DetailGuardStats stats;
    check(lunacore::detail_guard_rgba(image.data.data(), image.width, image.height, image.stride, stats) ==
          lunacore::DetailGuardError::none, "valid image rejected");
    return stats;
}
int y4(const std::uint8_t* p) { return p[0] + 2 * p[1] + p[2]; }
void invariants(const Image& before, const Image& after, lunacore::DetailGuardStats stats) {
    std::uint64_t changed = 0;
    for (int y = 0; y < before.height; ++y) {
        for (int x = 0; x < before.width; ++x) {
            const auto* a = before.at(x, y); const auto* b = after.at(x, y);
            const int delta = b[0] - a[0];
            check(std::abs(delta) <= 2 && b[1] - a[1] == delta && b[2] - a[2] == delta,
                  "change budget or channel differences violated");
            check(a[3] == b[3], "alpha changed");
            if (x < 2 || y < 2 || x + 2 >= before.width || y + 2 >= before.height)
                check(delta == 0, "two-pixel margin modified");
            changed += delta != 0 ? 1 : 0;
            if (delta != 0) check(a[3] == 255 && y4(a) >= 80 && y4(a) <= 940,
                                  "ineligible center modified");
        }
        for (std::size_t i = static_cast<std::size_t>(before.width) * 4; i < before.stride; ++i)
            check(before.data[y * before.stride + i] == after.data[y * before.stride + i], "padding changed");
    }
    check(changed == stats.denoised + stats.sharpened, "changed counts disagree with actual pixels");
}
double noise_rmse(const Image& image, int expected) {
    double squared = 0; int count = 0;
    for (int y = 2; y + 2 < image.height; ++y) {
        for (int x = 2; x + 2 < image.width; ++x) {
            const double difference = image.at(x, y)[0] - expected;
            squared += difference * difference; ++count;
        }
    }
    return std::sqrt(squared / count);
}
// Independent full-frame reference: direct coordinate reads and rational rules,
// unlike the production rolling cache. Also checks exact signed tie behavior.
int round_ratio(int n, int d) {
    return n >= 0 ? (n + d / 2) / d : -((-n + d / 2) / d);
}
Image reference(const Image& source) {
    auto out = source;
    constexpr int offsets[4][2] = {{-1,0},{0,-1},{-1,-1},{1,-1}};
    for (int y = 2; y + 2 < source.height; ++y) {
        for (int x = 2; x + 2 < source.width; ++x) {
            const auto* p = source.at(x, y); const int center = y4(p);
            if (p[3] != 255 || center < 80 || center > 940) continue;
            int low = center, high = center, votes = 0, sum = 0, pair_min = 2048, pair_max = 0;
            int strongest = 0, selected_detail = 0;
            bool opaque = true, coherent_line = false;
            for (const auto& direction : offsets) {
                const int dx = direction[0], dy = direction[1];
                const auto* a = source.at(x - dx, y - dy); const auto* b = source.at(x + dx, y + dy);
                const auto* aa = source.at(x - 2 * dx, y - 2 * dy);
                const auto* bb = source.at(x + 2 * dx, y + 2 * dy);
                opaque = opaque && a[3] == 255 && b[3] == 255 && aa[3] == 255 && bb[3] == 255;
                const int ya = y4(a), yb = y4(b), yaa = y4(aa), ybb = y4(bb);
                low = std::min(low, std::min(ya, yb)); high = std::max(high, std::max(ya, yb));
                coherent_line = coherent_line || (std::abs(ya-center) <= 4 && std::abs(yaa-center) <= 4) ||
                    (std::abs(yb-center) <= 4 && std::abs(ybb-center) <= 4);
                if (std::abs(ya - yb) <= 16 && std::abs((a[0] - a[2]) - (b[0] - b[2])) <= 12 &&
                    std::abs((4 * a[1] - ya) - (4 * b[1] - yb)) <= 24) {
                    ++votes; sum += ya + yb;
                    pair_min = std::min(pair_min, ya + yb); pair_max = std::max(pair_max, ya + yb);
                }
                const int g1 = yb - ya, g2 = ybb - yaa;
                const int d1 = 2 * center - ya - yb, d2 = 2 * center - yaa - ybb;
                if (g1 * g2 > 0 && std::abs(g1) >= 32 && std::abs(g2) >= std::abs(g1) &&
                    std::abs(g2) <= 4 * std::abs(g1) && d1 * d2 > 0 && std::abs(d2) >= std::abs(d1) &&
                    std::abs(d1) >= 8 && std::abs(g1) > strongest) {
                    const int nx = -dy, ny = dx;
                    const auto* h1a = source.at(x+nx+dx,y+ny+dy);
                    const auto* h1b = source.at(x+nx-dx,y+ny-dy);
                    const auto* h2a = source.at(x-nx+dx,y-ny+dy);
                    const auto* h2b = source.at(x-nx-dx,y-ny-dy);
                    if (h1a[3] == 255 && h1b[3] == 255 && h2a[3] == 255 && h2b[3] == 255 &&
                        std::abs(y4(h1a)-y4(h1b)-g1) <= 8 && std::abs(y4(h2a)-y4(h2b)-g1) <= 8) {
                        strongest = std::abs(g1); selected_detail = d1;
                    }
                }
            }
            if (!opaque) continue;
            int delta = 0;
            if (high - low <= 48) {
                if (high - low <= 20 || center == low || center == high ||
                    (high - low >= 8 && coherent_line)) continue;
                const int n = sum - 2 * votes * center;
                if (votes >= 3 && pair_max - pair_min <= 24 && std::abs(n) <= 48 * votes)
                    delta = std::clamp(round_ratio(7 * n, 160 * votes), -2, 2);
            } else if (strongest > 0) {
                delta = std::clamp(round_ratio(selected_detail, 32), -2, 2);
                while (center + 4 * delta < low) ++delta;
                while (center + 4 * delta > high) --delta;
            }
            delta = std::max(delta, -static_cast<int>(std::min({p[0],p[1],p[2]})));
            delta = std::min(delta, 255 - std::max({p[0],p[1],p[2]}));
            for (int c = 0; c < 3; ++c) out.at(x, y)[c] = static_cast<std::uint8_t>(p[c] + delta);
        }
    }
    return out;
}
void low_contrast_patterns() {
    constexpr const char* glyph[7] = {"10001", "10001", "10001", "11111", "10001", "10001", "10001"};
    constexpr const char* small_a[5] = {"111", "101", "111", "101", "101"};
    for (int pattern = 0; pattern < 7; ++pattern) {
        const char* names[] = {"vertical-1px", "vertical-2px", "weave-1px", "weave-2px", "thin-lines", "glyph5x7", "glyphA3x5"};
        for (const int contrast : {2,3,4,5,6,7,8,12}) {
            Image image(132,104);
            const auto foreground = [&](int x, int y) {
                if (pattern == 0) return x % 2 == 0;
                if (pattern == 1) return (x / 2) % 2 == 0;
                if (pattern == 2) return (x + y) % 2 == 0;
                if (pattern == 3) return (x / 2 + y / 2) % 2 == 0;
                if (pattern == 4) return x % 13 == 5;
                if (pattern == 6) return x % 5 < 3 && y % 7 < 5 && small_a[y % 7][x % 5] == '1';
                return x % 8 < 5 && y % 10 < 7 && glyph[y % 10][x % 8] == '1';
            };
            for (int y = 0; y < image.height; ++y) for (int x = 0; x < image.width; ++x)
                image.grey(x,y,128 + (foreground(x,y) ? contrast : 0));
            const auto original = image; const auto stats = run(image);
            double fg = 0, bg = 0, squared = 0; int nfg = 0, nbg = 0, retained = 0;
            for (int y = 2; y + 2 < image.height; ++y) {
                for (int x = 2; x + 2 < image.width; ++x) {
                    const int value = image.at(x,y)[0];
                    const int difference = value - original.at(x,y)[0];
                    squared += difference * difference;
                    // These source patterns have no intermediate shades. New values
                    // outside their extrema are an observable halo/overshoot.
                    check(value >= 128 && value <= 128 + contrast, "low-contrast pattern halo");
                    if (foreground(x,y)) { fg += value; ++nfg; retained += value * 2 > 256 + contrast ? 1 : 0; }
                    else { bg += value; ++nbg; }
                }
            }
            const double output_contrast = fg / nfg - bg / nbg;
            std::cout << "Texture " << names[pattern] << " contrast=" << contrast
                      << " -> " << output_contrast << ", retention=" << output_contrast / contrast
                      << ", ink-above-midpoint=" << static_cast<double>(retained) / nfg
                      << ", source-RMSE=" << std::sqrt(squared / (nfg + nbg))
                      << ", denoised=" << stats.denoised << ", sharpened=" << stats.sharpened << '\n';
            // Low-contrast losses are reported explicitly, not disguised as a
            // preservation pass. Stronger patterns must retain their source bytes.
            check(output_contrast >= 0, "texture polarity inverted");
            check(image.data == original.data, "protected thin texture changed");
            check(image.data == reference(original).data, "low contrast reference mismatch");
            invariants(original, image, stats);
        }
    }
    for (const double softness : {1.5,2.5,4.0}) for (const int sign : {-1,1}) {
        Image image(128,96);
        for (int y = 0; y < image.height; ++y) for (int x = 0; x < image.width; ++x)
            image.grey(x,y,static_cast<int>(std::lround(130 + sign * 50 * std::tanh((x - 63.5) / softness))));
        const auto original = image; const auto stats = run(image);
        for (int x = 2; x + 2 < image.width; ++x) {
            const int value = image.at(x,48)[0];
            check(value >= 80 && value <= 180, "soft-edge halo outside source extrema");
            check(sign * (image.at(x+1,48)[0] - value) >= 0, "soft-edge ringing reversal");
            if (x < 48 || x > 79) check(value == original.at(x,48)[0], "distant edge halo");
        }
        invariants(original, image, stats);
    }
    std::cout << "Six monotone soft-edge cases: no extrema overshoot, ringing reversal or distant halo\n";
}
}

int main(int argc, char** /* argv */) {
    try {
        lunacore::DetailGuardStats invalid;
        std::array<std::uint8_t, 100> bytes{};
        check(lunacore::detail_guard_rgba(nullptr, 5, 5, 20, invalid) == lunacore::DetailGuardError::invalid_image,
              "null image accepted");
        for (const auto dimensions : {std::array<std::size_t, 3>{0,5,20}, {5,5,19}, {16000001,1,64000004},
                                     {5,5,std::numeric_limits<std::size_t>::max()}})
            check(lunacore::detail_guard_rgba(bytes.data(), dimensions[0], dimensions[1], dimensions[2], invalid) ==
                  lunacore::DetailGuardError::invalid_image, "invalid dimensions accepted");
        for (const int size : {1,2,3,4}) {
            Image image(size, 8); image.fill(128); const auto original = image;
            run(image); check(image.data == original.data, "tiny image modified");
        }
        for (const int level : {0, 19, 20, 128, 235, 236, 255}) {
            Image image(27, 19); image.fill(level); const auto original = image;
            const auto stats = run(image); check(image.data == original.data, "flat/clipped image changed");
            invariants(original, image, stats);
        }
        for (const int amplitude : {3,5,8}) {
            Image image(256, 192); std::uint32_t state = 0x36a41bc9;
            for (int y = 0; y < image.height; ++y) for (int x = 0; x < image.width; ++x)
                image.grey(x, y, 128 + static_cast<int>(next(state) % (2 * amplitude + 1)) - amplitude);
            const auto original = image; auto duplicate = image;
            const auto stats = run(image), again = run(duplicate);
            const double before = noise_rmse(original, 128), after = noise_rmse(image, 128);
            std::cout << "Seeded grey noise +/-" << amplitude << " RMSE " << before << " -> " << after
                      << ", denoised=" << stats.denoised << ", sharpened=" << stats.sharpened << '\n';
            check(after <= before && stats.denoised > 0 && (amplitude > 5 || stats.sharpened == 0),
                  "seeded noise worsened or low-amplitude noise was sharpened");
            if (stats.sharpened > 0)
                std::cout << "QUALITY LIMIT: rare random-noise pixels still qualify for sharpening; aggregate RMSE must not rise\n";
            check(image.data == duplicate.data && stats.denoised == again.denoised, "nondeterministic result");
            check(image.data == reference(original).data, "noise reference mismatch");
            invariants(original, image, stats);
        }
        {
            Image image(128, 96);
            for (int y = 0; y < image.height; ++y) for (int x = 0; x < image.width; ++x)
                image.grey(x, y, static_cast<int>(std::lround(130 + 50 * std::tanh((x - 63.5) / 2.5))));
            const auto original = image; const auto stats = run(image);
            const double slope_before = (original.at(65,48)[0] - original.at(62,48)[0]) / 3.0;
            const double slope_after = (image.at(65,48)[0] - image.at(62,48)[0]) / 3.0;
            for (int x = 3; x + 2 < image.width; ++x) {
                check(image.at(x,48)[0] >= 80 && image.at(x,48)[0] <= 180, "soft edge overshoot");
                check(image.at(x,48)[0] >= image.at(x-1,48)[0], "soft edge gained nonmonotone ringing");
            }
            std::cout << "Soft-edge central 3-pixel mean slope " << slope_before << " -> " << slope_after
                      << ", sharpened=" << stats.sharpened << '\n';
            check(slope_after > slope_before && stats.sharpened > 0, "edge acutance not improved");
            check(image.data == reference(original).data, "soft-edge reference mismatch");
            invariants(original, image, stats);
        }
        for (const int period : {1,2,4}) {
            Image image(63, 57);
            for (int y = 0; y < image.height; ++y) for (int x = 0; x < image.width; ++x)
                image.grey(x, y, ((x / period + y / period) % 2) ? 180 : 80);
            const auto original = image; run(image);
            check(image.data == original.data, "high contrast fine texture changed");
        }
        for (const int sign : {-1,1}) {
            Image image(9,9); image.fill(128);
            for (int y = 0; y < image.height; ++y) {
                if (sign > 0) {
                    image.grey(5,y,118); image.grey(3,y,134);
                    image.grey(6,y,108); image.grey(2,y,138);
                } else {
                    image.grey(5,y,122); image.grey(3,y,138);
                    image.grey(6,y,118); image.grey(2,y,148);
                }
            }
            const auto original = image; const auto stats = run(image);
            check(image.at(4,4)[0] == 128 + sign, "signed half-step must round away from zero");
            invariants(original, image, stats);
        }
        for (const bool upper : {false,true}) {
            Image image(9,9);
            const auto set = [&](int x, int r, int g) {
                for (int y = 0; y < image.height; ++y) {
                    auto* p = image.at(x,y); p[0] = static_cast<std::uint8_t>(r);
                    p[1] = p[2] = static_cast<std::uint8_t>(g); p[3] = 255;
                }
            };
            for (int y = 0; y < image.height; ++y) for (int x = 0; x < image.width; ++x) {
                auto* p = image.at(x,y); p[0] = upper ? 255 : 0;
                p[1] = p[2] = upper ? 127 : 128; p[3] = 255;
            }
            if (upper) { set(5,245,117); set(3,255,133); set(6,235,107); set(2,255,137); }
            else { set(5,0,122); set(3,10,138); set(6,0,118); set(2,20,148); }
            const auto original = image; const auto stats = run(image);
            check(std::equal(original.at(4,4), original.at(4,4)+4, image.at(4,4)),
                  "channel headroom clamp failed");
            invariants(original, image, stats);
        }
        constexpr int directions[4][2] = {{-1,0},{0,-1},{-1,-1},{1,-1}};
        {
            Image image(9,9); image.fill(128); image.grey(4,4,132); image.grey(5,4,135);
            run(image); check(image.at(4,4)[0] == 131, "alpha protection control does not exercise denoise");
        }
        for (const auto& d : directions) for (const int distance : {-2,-1,1,2}) {
            Image image(9,9); image.fill(128); image.grey(4,4,132); image.grey(5,4,135);
            image.at(4 + d[0] * distance, 4 + d[1] * distance)[3] = 127;
            const auto original = image; const auto stats = run(image);
            check(image.at(4,4)[0] == 132, "alpha sample did not protect center");
            invariants(original, image, stats);
        }
        std::uint64_t compared = 0, reference_changes = 0;
        for (unsigned seed = 1; seed <= 40; ++seed) {
            Image image(33 + seed % 9, 29 + seed % 7); std::uint32_t state = seed * 127301U;
            for (int y = 0; y < image.height; ++y) for (int x = 0; x < image.width; ++x) {
                auto* p = image.at(x,y);
                for (int c = 0; c < 3; ++c) {
                    const auto random = next(state);
                    // Alternate arbitrary RGBA and low-amplitude color noise;
                    // coherent-edge rejection correctly leaves arbitrary RGB alone.
                    p[c] = static_cast<std::uint8_t>(seed % 2 ?
                        100 + 20 * c + static_cast<int>(random % 9) - 4 : random & 255U);
                }
                p[3] = next(state) % 19 == 0 ? 0 : 255;
            }
            const auto original = image; const auto stats = run(image);
            check(image.data == reference(original).data, "random RGBA reference mismatch");
            invariants(original, image, stats); compared += image.width * image.height;
            reference_changes += stats.denoised + stats.sharpened;
        }
        check(reference_changes > 0, "random reference fixture did not exercise changed pixels");
        std::cout << "Exact direct-reference random pixels=" << compared
                  << ", changed=" << reference_changes << '\n';
        low_contrast_patterns();
        if (argc > 1) {
            Image image(4080,3060); std::uint32_t state = 0x36a41bc9;
            for (int y = 0; y < image.height; ++y) for (int x = 0; x < image.width; ++x)
                image.grey(x,y,128 + static_cast<int>(next(state) % 7) - 3);
            const auto start = std::chrono::steady_clock::now();
            const auto stats = run(image);
            const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - start).count();
            std::cout << "12MP desktop DetailGuard " << ms << " ms, denoised=" << stats.denoised
                      << ", sharpened=" << stats.sharpened << '\n';
        }
        std::cout << "DetailGuard quality, exact integer reference, bounds, alpha and invariants passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n'; return 1;
    }
}
