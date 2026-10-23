#pragma once
#include <cstddef>
#include <cstdint>

namespace lunacore {
struct SerRenderShading {
    const float* gains;
    int columns, rows;
    int left, top, right, bottom;
};
// metadata: blackLevels[4 raster positions], white, gains[R,Ge,Go,B], matrix[9 row-major].
bool ser_render(const std::uint32_t* packed, int width, int height, int cfa,
                const float* metadata, std::uint8_t* rgba, std::size_t stride,
                const SerRenderShading* shading=nullptr) noexcept;
struct SerNaturalTone {
    float exposureGain; // 1..16: restores measured/bracket exposure, not clipped sensor detail.
    float shadowStrength; // 0..1: bounded, global pointwise shadow lift.
    float noiseFloor; // 0..0.1: heuristic linear-light floor, not calibrated sensor noise.
};
// Separate daytime renderer; ignores packed Night EV bits. Invalid input never changes rgba.
bool ser_render_natural(const std::uint32_t* packed, int width, int height, int cfa,
                        const float* metadata, std::uint8_t* rgba, std::size_t stride,
                        const SerNaturalTone& tone,
                        const SerRenderShading* shading=nullptr) noexcept;
}
