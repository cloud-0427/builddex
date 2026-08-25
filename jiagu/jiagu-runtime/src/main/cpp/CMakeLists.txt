cmake_minimum_required(VERSION 3.13)
project("jiagu-core")

add_library(jiagu-core SHARED jiagu-core.cpp)

# These are standard NDK hardening/optimization flags. They are deliberately target-scoped
# so -O3 appears after CMake's RelWithDebInfo -O2 and is therefore the effective level.
# Real OLLVM passes require a custom LLVM toolchain; NDK 28.2's stock Clang has no
# -sub/-fla/-sobf passes, so adding those flags here would only break the build.
target_compile_options(jiagu-core PRIVATE
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        $<$<OR:$<CONFIG:Release>,$<CONFIG:RelWithDebInfo>>:-O3>)

target_link_options(jiagu-core PRIVATE
        -Wl,--exclude-libs,ALL
        -Wl,--gc-sections
        -Wl,--strip-all)

find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(jiagu-core ${log-lib} ${android-lib})
