#pragma once
// Media wire format — must match native/field-encode/Sources/field-encode/Protocol.swift's
// `Wire` enum byte-for-byte. See ../../field-encode/WIRE_FORMAT.md (the shared contract).
#include <cstdint>
#include <cstring>
#include <vector>

namespace field_encode::Wire {

constexpr uint8_t version = 1;
constexpr size_t headerSize = 20;
constexpr size_t maxPayload = 1200;

constexpr uint8_t flagKeyframe = 1 << 0;
constexpr uint8_t flagFrameEnd = 1 << 1;
constexpr uint8_t flagParamSets = 1 << 2;
constexpr uint8_t flagStereoSbs = 1 << 3;

constexpr uint8_t codecHEVC = 0;
constexpr uint8_t codecH264 = 1;

inline void appendBE16(std::vector<uint8_t>& b, uint16_t v) {
    b.push_back(uint8_t((v >> 8) & 0xff));
    b.push_back(uint8_t(v & 0xff));
}
inline void appendBE32(std::vector<uint8_t>& b, uint32_t v) {
    for (int shift = 24; shift >= 0; shift -= 8) b.push_back(uint8_t((v >> shift) & 0xff));
}
inline void appendBE64(std::vector<uint8_t>& b, uint64_t v) {
    for (int shift = 56; shift >= 0; shift -= 8) b.push_back(uint8_t((v >> shift) & 0xff));
}

inline std::vector<uint8_t> header(uint8_t flags, uint8_t codec, uint32_t frameSeq,
                                    uint16_t fragIndex, uint16_t fragCount, uint64_t ptsMicros) {
    std::vector<uint8_t> b;
    b.reserve(headerSize);
    b.push_back(version);
    b.push_back(flags);
    b.push_back(codec);
    b.push_back(0); // reserved
    appendBE32(b, frameSeq);
    appendBE16(b, fragIndex);
    appendBE16(b, fragCount);
    appendBE64(b, ptsMicros);
    return b;
}

} // namespace field_encode::Wire
