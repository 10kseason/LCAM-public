#include <algorithm>
#include <cmath>
#include <cstdint>
#include <iostream>

// Exhaustive numeric-domain check for the experimental integer GLES shader.
// No GL/device is needed; this is deliberately independent of production selection.
int floor_div(int n, int d) { return n >= 0 ? n / d : -((-n + d - 1) / d); }

bool verify(int center_limit, int sum_limit, int divisor, int clamp_limit,
            std::uint64_t& checked) {
    for (int votes : {3, 4}) {
        for (int center = -center_limit; center <= center_limit; ++center) {
            for (int sum = -sum_limit; sum <= sum_limit; ++sum) {
                const float residual = (static_cast<float>(sum) / divisor) / votes -
                    static_cast<float>(center) / (divisor / 2);
                const int n = sum - 2 * votes * center;
                const int d = divisor * votes;
                const bool native_reject = std::abs(residual) > 12.0f;
                const bool integer_reject = std::abs(n) > 12 * d;
                if (native_reject != integer_reject) return false;
                if (!native_reject) {
                    const int native_delta = std::clamp(static_cast<int>(std::floor(
                        static_cast<double>(residual * 0.35f) + 0.5)),
                        -clamp_limit, clamp_limit);
                    const int integer_delta = std::clamp(floor_div(7 * n + 10 * d, 20 * d),
                                                         -clamp_limit, clamp_limit);
                    if (native_delta != integer_delta) {
                        std::cerr << "mismatch sum=" << sum << " center=" << center
                                  << " votes=" << votes << '\n';
                        return false;
                    }
                }
                ++checked;
            }
        }
    }
    return true;
}

int main() {
    std::uint64_t checked = 0;
    // co2 in [-255,255], cg4 in [-510,510]; at most eight neighbours.
    if (!verify(255, 2040, 4, 2, checked) || !verify(510, 4080, 8, 1, checked)) return 1;
    std::cout << "GPU rational residual parity: " << checked << " cases passed\n";
}
