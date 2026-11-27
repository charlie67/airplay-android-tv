cmake_minimum_required(VERSION 3.22.1)
project(airplayserver C)

set(LIB ${CMAKE_CURRENT_SOURCE_DIR}/airplay/lib)

# Vendored libplist (binary + XML property lists).
file(GLOB PLIST_SRC ${LIB}/plist/*.c)
add_library(plist STATIC ${PLIST_SRC})
target_include_directories(plist PRIVATE ${LIB}/plist)
target_compile_options(plist PRIVATE -w)

# Small crypto helpers (AES, SHA1, MD5, bigint) used by RSA/FairPlay/pairing.
file(GLOB CRYPTO_SRC ${LIB}/crypto/*.c)
add_library(apcrypto STATIC ${CRYPTO_SRC})
target_compile_options(apcrypto PRIVATE -w)

file(GLOB CURVE_SRC ${LIB}/curve25519/*.c)
add_library(curve25519 STATIC ${CURVE_SRC})
target_compile_options(curve25519 PRIVATE -w)

file(GLOB ED_SRC ${LIB}/ed25519/*.c)
add_library(ed25519 STATIC ${ED_SRC})
target_compile_options(ed25519 PRIVATE -w)

file(GLOB PLAYFAIR_SRC ${LIB}/playfair/*.c)
add_library(playfair STATIC ${PLAYFAIR_SRC})
target_include_directories(playfair PRIVATE ${LIB}/playfair)
target_compile_options(playfair PRIVATE -w)

# AirPlay / RAOP protocol core (from AirPlayServerLib). dnssd.c and pinpair.c
# are not copied: discovery is done with Android's NsdManager and PIN pairing
# is not supported.
file(GLOB CORE_SRC ${LIB}/*.c)
add_library(airplayserver SHARED
        ${CORE_SRC}
        pinpair_stub.c
        airplay_jni.c)

target_include_directories(airplayserver PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/compat
        ${CMAKE_CURRENT_SOURCE_DIR}/airplay/include
        ${LIB}
        ${LIB}/crypto
        ${LIB}/curve25519
        ${LIB}/ed25519
        ${LIB}/playfair)

target_compile_definitions(airplayserver PRIVATE "__int64=long long")
target_compile_options(airplayserver PRIVATE
        -Wno-unused-parameter -Wno-unused-variable -Wno-unused-function
        -Wno-pointer-sign -Wno-incompatible-pointer-types-discards-qualifiers
        -Wno-sign-compare -Wno-missing-field-initializers)

target_link_libraries(airplayserver
        plist apcrypto curve25519 ed25519 playfair
        mediandk android log)

# 16 KB page size support (required for Android 15+ devices).
target_link_options(airplayserver PRIVATE "-Wl,-z,max-page-size=16384")
