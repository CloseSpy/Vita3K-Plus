// Vita3K emulator project
// Copyright (C) 2026 Vita3K team
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>

#include <mem/functions.h>
#include <mem/state.h>

namespace renderer::vertex_diagnostics {

// Diagnostic fingerprint, NOT a checksum of the entire stream. The first/last
// 128 bytes catch recycled guest buffers while keeping sampling inexpensive.
inline uint64_t fingerprint_bytes(const uint8_t *data, const size_t size) {
    constexpr uint64_t basis = 14695981039346656037ull;
    constexpr uint64_t prime = 1099511628211ull;
    uint64_t hash = basis;
    const auto mix = [&](uint8_t byte) { hash = (hash ^ byte) * prime; };
    for (unsigned n = 0; n < sizeof(size); n++)
        mix(static_cast<uint8_t>(size >> (8 * n)));
    const size_t first = std::min<size_t>(size, 128);
    for (size_t i = 0; i < first; ++i)
        mix(data[i]);
    if (size > first) {
        const size_t last = std::min<size_t>(size - first, 128);
        for (size_t i = size - last; i < size; ++i)
            mix(data[i]);
    }
    return hash;
}

inline bool fingerprint_guest(MemState &mem, const Address addr, const size_t size, uint64_t &result) {
    // Check the full range so sampling never reads unallocated guest pages.
    if (!addr || !size || size > 16 * 1024 * 1024 || size > UINT32_MAX - addr
        || !is_valid_addr_range(mem, addr, static_cast<Address>(addr + size)))
        return false;

    uint8_t head[128]{};
    uint8_t tail[128]{};
    const size_t first = std::min<size_t>(size, sizeof(head));
    memcpy_from_guest(mem, head, addr, static_cast<uint32_t>(first));
    const size_t last = size > first ? std::min<size_t>(size - first, sizeof(tail)) : 0;
    if (last)
        memcpy_from_guest(mem, tail, static_cast<Address>(addr + size - last), static_cast<uint32_t>(last));

    // Match fingerprint_bytes: size prefix, then first and last bytes.
    constexpr uint64_t prime = 1099511628211ull;
    result = 14695981039346656037ull;
    const auto mix = [&](uint8_t byte) { result = (result ^ byte) * prime; };
    for (unsigned n = 0; n < sizeof(size); n++)
        mix(static_cast<uint8_t>(size >> (8 * n)));
    for (size_t i = 0; i < first; ++i)
        mix(head[i]);
    for (size_t i = 0; i < last; ++i)
        mix(tail[i]);
    return true;
}

} // namespace renderer::vertex_diagnostics
