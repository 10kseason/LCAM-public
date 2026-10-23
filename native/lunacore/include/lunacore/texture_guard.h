#pragma once

#include <cstddef>
#include <cstdint>

namespace lunacore {
enum class TextureGuardError { none, invalid_image, allocation_failed };

// RGBA byte order; row padding and all border/transparent pixels remain untouched.
// The three original rows are retained so in-place output never feeds back as input.
TextureGuardError texture_guard_rgba(
    std::uint8_t* pixels, std::size_t width, std::size_t height,
    std::size_t stride, std::uint64_t& changed) noexcept;
}
