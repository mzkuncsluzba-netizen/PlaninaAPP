cmake_minimum_required(VERSION 3.22.1)
project("planinaambit" C CXX)

# Watch protocol: libambit from openambit (Emil Ljungdahl, GPLv3) with the Ambit3 Bluetooth
# transport and Android glue from Sommet (André Faria, GPLv3, github.com/skinnie/sommet).
# Copied unchanged; see CREDITS.md at the repository root.

set(CMAKE_CXX_STANDARD 17)

file(GLOB LIBAMBIT_SOURCES
    "libambit/*.c"
    "libambit/hidapi/hid-android.c"
)
add_library(ambit STATIC ${LIBAMBIT_SOURCES}
    shared/device_driver_ambit3_navigation.c
    shared/device_driver_ambit3_sport_modes.c
)
set_target_properties(ambit PROPERTIES C_STANDARD 11)
target_include_directories(ambit PUBLIC libambit libambit/hidapi shared)
target_compile_options(ambit PRIVATE -include endian.h)
target_compile_definitions(ambit PRIVATE DEBUG_PRINT_INFO DEBUG_PRINT_WARNING DEBUG_PRINT_ERROR)
target_link_libraries(ambit log)

add_library(planinaambit SHARED jni_bridge.cpp)
target_include_directories(planinaambit PRIVATE libambit shared)
target_link_libraries(planinaambit ambit log)
