#include <cstdlib>
#include <string_view>

#include "lunacore/version.h"

int main() {
    constexpr std::string_view expected_version = "0.2.0-p1";
    const std::string_view actual_version = lunacore::version();
    return actual_version == expected_version ? EXIT_SUCCESS : EXIT_FAILURE;
}
