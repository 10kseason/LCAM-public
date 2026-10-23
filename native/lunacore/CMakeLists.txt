cmake_minimum_required(VERSION 3.22.1)

project(lunacore LANGUAGES CXX)

option(LUNACORE_BUILD_HOST_TESTS "Build host-side LunaCore smoke tests" OFF)

add_library(lunacore_version STATIC
    src/version.cpp
)

set_target_properties(lunacore_version PROPERTIES
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED YES
    CXX_EXTENSIONS NO
    POSITION_INDEPENDENT_CODE ON
)

target_include_directories(lunacore_version
    PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

function(lunacore_enable_strict_warnings target_name)
    if(MSVC)
        target_compile_options(${target_name} PRIVATE /W4 /WX /permissive-)
    else()
        target_compile_options(${target_name} PRIVATE -Wall -Wextra -Wpedantic -Werror)
    endif()
endfunction()

lunacore_enable_strict_warnings(lunacore_version)

add_library(lunacore_pixels STATIC src/texture_guard.cpp src/detail_guard.cpp src/raw_fusion.cpp src/hdr_fusion.cpp
    src/ser_night.cpp src/ser_render.cpp src/raw_hdr.cpp)
set_target_properties(lunacore_pixels PROPERTIES
    CXX_STANDARD 20 CXX_STANDARD_REQUIRED YES CXX_EXTENSIONS NO POSITION_INDEPENDENT_CODE ON)
target_include_directories(lunacore_pixels PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
# The shipping debug APK must not run its per-pixel loops at -O0. No fast math:
# exact Texture Guard decisions depend on IEEE float operation order.
if(MSVC)
    target_compile_options(lunacore_pixels PRIVATE /O2 /fp:strict)
else()
    target_compile_options(lunacore_pixels PRIVATE -O3 -fno-fast-math -ffp-contract=off)
endif()
lunacore_enable_strict_warnings(lunacore_pixels)

if(ANDROID)
    add_library(lunacore SHARED
        src/jni_bridge.cpp
        src/texture_guard_jni.cpp
        src/detail_guard_jni.cpp
        src/raw_fusion_jni.cpp
        src/hdr_fusion_jni.cpp
        src/ser_night_jni.cpp
        src/ser_render_jni.cpp
        src/raw_hdr_jni.cpp
    )

    set_target_properties(lunacore PROPERTIES
        CXX_STANDARD 20
        CXX_STANDARD_REQUIRED YES
        CXX_EXTENSIONS NO
    )

    target_link_libraries(lunacore PRIVATE lunacore_version lunacore_pixels jnigraphics)
    target_compile_options(lunacore PRIVATE -fno-exceptions -fno-rtti)
    # This bridge bounds and catches its small plan-vector allocation.
    set_source_files_properties(src/ser_night_jni.cpp PROPERTIES COMPILE_OPTIONS "-fexceptions")
    lunacore_enable_strict_warnings(lunacore)
elseif(LUNACORE_BUILD_HOST_TESTS)
    enable_testing()

    add_executable(lunacore_version_test
        tests/version_test.cpp
    )

    set_target_properties(lunacore_version_test PROPERTIES
        CXX_STANDARD 20
        CXX_STANDARD_REQUIRED YES
        CXX_EXTENSIONS NO
    )

    target_link_libraries(lunacore_version_test PRIVATE lunacore_version)
    lunacore_enable_strict_warnings(lunacore_version_test)

    add_test(NAME lunacore.version COMMAND lunacore_version_test)

    foreach(test_name texture_guard texture_guard_gpu_math detail_guard raw_fusion hdr_fusion ser_night ser_render raw_hdr)
        add_executable(lunacore_${test_name}_test tests/${test_name}_test.cpp)
        set_target_properties(lunacore_${test_name}_test PROPERTIES
            CXX_STANDARD 20 CXX_STANDARD_REQUIRED YES CXX_EXTENSIONS NO)
        target_link_libraries(lunacore_${test_name}_test PRIVATE lunacore_pixels)
        if(NOT MSVC)
            target_compile_options(lunacore_${test_name}_test PRIVATE -fno-fast-math -ffp-contract=off)
        endif()
        lunacore_enable_strict_warnings(lunacore_${test_name}_test)
        add_test(NAME lunacore.${test_name} COMMAND lunacore_${test_name}_test)
    endforeach()
endif()
