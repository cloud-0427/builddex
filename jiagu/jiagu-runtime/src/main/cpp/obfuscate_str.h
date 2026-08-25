#ifndef JIAGU_OBFUSCATE_STR_H
#define JIAGU_OBFUSCATE_STR_H

#include <cstddef>

namespace jiagu {

template <size_t N, unsigned char K>
struct XorString {
    char data[N];
    bool decrypted;

    constexpr XorString(const char* s) : data{}, decrypted(false) {
        for (size_t i = 0; i < N; ++i) {
            data[i] = static_cast<char>(s[i] ^ K);
        }
    }

    __attribute__((always_inline)) const char* decrypt() {
        if (!decrypted) {
            for (size_t i = 0; i < N; ++i) {
                data[i] ^= K;
            }
            decrypted = true;
        }
        return data;
    }
};

#define OBFUSCATE_KEY 0xAD

#define X(str) []() { \
    static auto xor_str = jiagu::XorString<sizeof(str), OBFUSCATE_KEY>(str); \
    return xor_str.decrypt(); \
}()

}

#endif //JIAGU_OBFUSCATE_STR_H
