// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "bucketid.h"
#include <iomanip>
#include <vespa/vespalib/util/exceptions.h>
#include <vespa/vespalib/objects/nbostream.h>
#include <vespa/vespalib/stllike/asciistream.h>
#include <vespa/vespalib/stllike/hash_set.hpp>
#include <vespa/vespalib/util/stringfmt.h>
#include <limits>
#include <xxhash.h>

using vespalib::nbostream;
using vespalib::asciistream;

namespace document {


const unsigned char reverseBitTable[256] =
{
  0x00, 0x80, 0x40, 0xC0, 0x20, 0xA0, 0x60, 0xE0, 0x10, 0x90, 0x50, 0xD0, 0x30, 0xB0, 0x70, 0xF0,
  0x08, 0x88, 0x48, 0xC8, 0x28, 0xA8, 0x68, 0xE8, 0x18, 0x98, 0x58, 0xD8, 0x38, 0xB8, 0x78, 0xF8,
  0x04, 0x84, 0x44, 0xC4, 0x24, 0xA4, 0x64, 0xE4, 0x14, 0x94, 0x54, 0xD4, 0x34, 0xB4, 0x74, 0xF4,
  0x0C, 0x8C, 0x4C, 0xCC, 0x2C, 0xAC, 0x6C, 0xEC, 0x1C, 0x9C, 0x5C, 0xDC, 0x3C, 0xBC, 0x7C, 0xFC,
  0x02, 0x82, 0x42, 0xC2, 0x22, 0xA2, 0x62, 0xE2, 0x12, 0x92, 0x52, 0xD2, 0x32, 0xB2, 0x72, 0xF2,
  0x0A, 0x8A, 0x4A, 0xCA, 0x2A, 0xAA, 0x6A, 0xEA, 0x1A, 0x9A, 0x5A, 0xDA, 0x3A, 0xBA, 0x7A, 0xFA,
  0x06, 0x86, 0x46, 0xC6, 0x26, 0xA6, 0x66, 0xE6, 0x16, 0x96, 0x56, 0xD6, 0x36, 0xB6, 0x76, 0xF6,
  0x0E, 0x8E, 0x4E, 0xCE, 0x2E, 0xAE, 0x6E, 0xEE, 0x1E, 0x9E, 0x5E, 0xDE, 0x3E, 0xBE, 0x7E, 0xFE,
  0x01, 0x81, 0x41, 0xC1, 0x21, 0xA1, 0x61, 0xE1, 0x11, 0x91, 0x51, 0xD1, 0x31, 0xB1, 0x71, 0xF1,
  0x09, 0x89, 0x49, 0xC9, 0x29, 0xA9, 0x69, 0xE9, 0x19, 0x99, 0x59, 0xD9, 0x39, 0xB9, 0x79, 0xF9,
  0x05, 0x85, 0x45, 0xC5, 0x25, 0xA5, 0x65, 0xE5, 0x15, 0x95, 0x55, 0xD5, 0x35, 0xB5, 0x75, 0xF5,
  0x0D, 0x8D, 0x4D, 0xCD, 0x2D, 0xAD, 0x6D, 0xED, 0x1D, 0x9D, 0x5D, 0xDD, 0x3D, 0xBD, 0x7D, 0xFD,
  0x03, 0x83, 0x43, 0xC3, 0x23, 0xA3, 0x63, 0xE3, 0x13, 0x93, 0x53, 0xD3, 0x33, 0xB3, 0x73, 0xF3,
  0x0B, 0x8B, 0x4B, 0xCB, 0x2B, 0xAB, 0x6B, 0xEB, 0x1B, 0x9B, 0x5B, 0xDB, 0x3B, 0xBB, 0x7B, 0xFB,
  0x07, 0x87, 0x47, 0xC7, 0x27, 0xA7, 0x67, 0xE7, 0x17, 0x97, 0x57, 0xD7, 0x37, 0xB7, 0x77, 0xF7,
  0x0F, 0x8F, 0x4F, 0xCF, 0x2F, 0xAF, 0x6F, 0xEF, 0x1F, 0x9F, 0x5F, 0xDF, 0x3F, 0xBF, 0x7F, 0xFF
};

BucketId::Type BucketId::_usedMasks[BucketId::maxNumBits+1];
BucketId::Type BucketId::_stripMasks[BucketId::maxNumBits+1];

namespace {

void fillUsedMasks(BucketId::Type * masks, uint8_t maxBits)
{
    using Type = BucketId::Type;
    for (uint32_t usedBits = 0; usedBits <= maxBits; ++usedBits) {
        uint8_t notused = 8 * sizeof(Type) - usedBits;
        masks[usedBits] = (usedBits > 0) ? ((std::numeric_limits<Type>::max() << notused) >> notused) : std::numeric_limits<Type>::max();
    }
}

void fillStripMasks(BucketId::Type * masks, uint8_t maxBits)
{
    using Type = BucketId::Type;
    for (uint32_t usedBits = 0; usedBits <= maxBits; ++usedBits) {
        uint8_t notused = 8 * sizeof(Type) - usedBits;
        Type usedMask = (usedBits > 0) ? ((std::numeric_limits<Type>::max() << notused) >> notused) : std::numeric_limits<Type>::max();
        Type countMask = (std::numeric_limits<Type>::max() >> maxBits) << maxBits;
        masks[usedBits] = usedMask | countMask;
    }
}


struct Initialize {
    Initialize() {
        BucketId::initialize();
    }
};

Initialize _initializeUsedMasks;

}

void BucketId::initialize() noexcept {
    fillUsedMasks(BucketId::_usedMasks, BucketId::maxNumBits);
    fillStripMasks(BucketId::_stripMasks, BucketId::maxNumBits);
}

uint64_t
BucketId::hash::operator () (const BucketId& bucketId) const noexcept {
    const uint64_t raw_id = bucketId.getId();
    /*
     * This is a workaround for gcc 12 and on that produces incorrect warning
     * /home/balder/git/vespa/document/src/vespa/document/bucket/bucketid.cpp: In member function ‘uint64_t document::BucketId::hash::operator()(const document::BucketId&) const’:
     * /home/balder/git/vespa/document/src/vespa/document/bucket/bucketid.cpp:83:23: error: ‘raw_id’ may be used uninitialized [-Werror=maybe-uninitialized]
     *   83 |     return XXH3_64bits(&raw_id, sizeof(uint64_t));
     *      |                       ^
     * In file included from /usr/include/xxh3.h:55,
     *            from /home/balder/git/vespa/document/src/vespa/document/bucket/bucketid.cpp:11:
     * /usr/include/xxhash.h:5719:29: note: by argument 1 of type ‘const void*’ to ‘XXH64_hash_t XXH_INLINE_XXH3_64bits(const void*, size_t)’ declared here
     * 5719 | XXH_PUBLIC_API XXH64_hash_t XXH3_64bits(XXH_NOESCAPE const void* input, size_t length)
     *      |                             ^~~~~~~~~~~
     * /home/balder/git/vespa/document/src/vespa/document/bucket/bucketid.cpp:82:14: note: ‘raw_id’ declared here
     *   82 |     uint64_t raw_id = bucketId.getId();
     *    |              ^~~~~~
     * cc1plus: all warnings being treated as errors
     */
    uint8_t raw_as_bytes[sizeof(raw_id)];
    memcpy(raw_as_bytes, &raw_id, sizeof(raw_id));

    return XXH3_64bits(raw_as_bytes, sizeof(raw_as_bytes));
}

vespalib::string
BucketId::toString() const
{
    vespalib::asciistream stream;
    stream << *this;
    return stream.str();
}

void BucketId::throwFailedSetUsedBits(uint32_t used, uint32_t availBits) {
    throw vespalib::IllegalArgumentException(vespalib::make_string(
            "Failed to set used bits to %u, max is %u.",
            used, availBits), VESPA_STRLOC);
}

BucketId::Type
BucketId::reverse(Type id) noexcept
{
    id = ((id & 0x5555555555555555l) << 1) | ((id & 0xaaaaaaaaaaaaaaaal) >> 1);
    id = ((id & 0x3333333333333333l) << 2) | ((id & 0xccccccccccccccccl) >> 2);
    id = ((id & 0x0f0f0f0f0f0f0f0fl) << 4) | ((id & 0xf0f0f0f0f0f0f0f0l) >> 4);
    return __builtin_bswap64(id);
}

BucketId::Type
BucketId::keyToBucketId(Type key) noexcept
{
    Type retVal = reverse(key);

    Type usedCountMSB = key << maxNumBits;
    retVal <<= CountBits;
    retVal >>= CountBits;
    retVal |= usedCountMSB;

    return retVal;
}

bool
BucketId::contains(const BucketId& id) const noexcept
{
    if (id.getUsedBits() < getUsedBits()) {
        return false;
    }
    BucketId copy(getUsedBits(), id.getRawId());
    return (copy.getId() == getId());
}

vespalib::asciistream& operator<<(asciistream& os, const BucketId& id)
{
    asciistream::StateSaver stateSaver(os);
    return os << "BucketId(0x"
              << vespalib::hex << vespalib::setw(sizeof(BucketId::Type)*2) << vespalib::setfill('0')
              << id.getId() << ")";
}

std::ostream& operator<<(std::ostream& os, const BucketId& id)
{
    return os << id.toString();
}

nbostream &
operator<<(nbostream &os, const BucketId &bucketId)
{
    os << bucketId._id;
    return os;
}

nbostream &
operator>>(nbostream &is, BucketId &bucketId)
{
    is >> bucketId._id;
    return is;
}

} // document

VESPALIB_HASH_SET_INSTANTIATE_H(document::BucketId, document::BucketId::hash);
