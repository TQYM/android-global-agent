// SPDX-License-Identifier: Apache-2.0
//
// CAgentLiteShim 实现 —— CommonCrypto 的 PBKDF2 包装。
//
// 注意：`password` 是**用户自选的账号密钥**，熵低，所以用 PBKDF2 的迭代拉伸
// 而非 HKDF。参数必须与服务端 `LANKeyIterations=100_000`、Java `KDF_ITERATIONS`
// 完全一致，否则局域网两端密钥不同、P2P 永远静默不通。

#include "CAgentLiteShim.h"
#include <CommonCrypto/CommonCryptoError.h>
#include <CommonCrypto/CommonKeyDerivation.h>

int agentlite_pbkdf2_sha256(
    const char *password, size_t password_len,
    const uint8_t *salt, size_t salt_len,
    uint32_t iterations,
    uint8_t *out, size_t out_len
) {
    if (password == NULL || salt == NULL || out == NULL) {
        return kCCParamError;
    }
    return CCKeyDerivationPBKDF(
        kCCPBKDF2,
        password, password_len,
        salt, salt_len,
        kCCPRFHmacAlgSHA256,
        iterations,
        out, out_len
    );
}
