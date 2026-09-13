// SPDX-License-Identifier: Apache-2.0
//
// CAgentLiteShim —— 只做一件事：把 CommonCrypto 的 PBKDF2 暴露给 Swift。
//
// 为什么不写自研 PBKDF2：跨端一致性是本项目的硬约束（密钥差一个字节，
// 局域网通道就永远静默不通）。用系统实现 + 交叉测试向量，比自研更容易证明正确。
//
// 为什么这里刻意**不** include CommonCrypto：这个头会被 Swift Package Manager
// 当作模块的公共接口（umbrella header）解析，而 CommonCrypto 是系统模块，
// 在 SPM 的 C 目标头文件上下文里不可见。因此公共头只声明一个不依赖系统类型的
// 函数签名（`int` + 基本类型），CommonCrypto 只在 .c 实现里使用。

#ifndef AGENTLITE_SHIM_H
#define AGENTLITE_SHIM_H

#include <stddef.h>
#include <stdint.h>

/// PBKDF2-HMAC-SHA256。
/// - Returns: 0 表示成功，其它值为 CommonCrypto 错误码（负值）。
int agentlite_pbkdf2_sha256(
    const char *password, size_t password_len,
    const uint8_t *salt, size_t salt_len,
    uint32_t iterations,
    uint8_t *out, size_t out_len
);

#endif /* AGENTLITE_SHIM_H */
