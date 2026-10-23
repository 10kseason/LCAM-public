#pragma once
#include <cstddef>
#include <cstdint>

namespace lunacore {
enum class DetailGuardError { none, invalid_image, allocation_failed };
struct DetailGuardStats {
    std::uint64_t denoised = 0;
    std::uint64_t sharpened = 0;
};
// Detail Guard 0.1 integer contract. In-place RGBA8, immutable source decisions,
// unchanged alpha/padding/two-pixel border. At most +/-2 per RGB channel.
DetailGuardError detail_guard_rgba(std::uint8_t* pixels, std::size_t width,
                                  std::size_t height, std::size_t stride,
                                  DetailGuardStats& stats) noexcept;
}
