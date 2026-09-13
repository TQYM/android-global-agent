// SPDX-License-Identifier: Apache-2.0
//
// 密码学交叉验证。**这是 ios/ 目录里最重要的一组测试**。
//
// 为什么：局域网 P2P 的密钥派生如果与 Android/服务端不一致，故障表现是
// 「P2P 一直连不上」，没有任何报错指向参数差异 —— 排查成本极高。所以这里把
// 期望值固定成**由 Java 侧真实生成的向量**（_local/ios-e2e/java/VectorGen.java，
// 参数逐行照抄 LanSync.java），Swift 侧只要算错一个字节就会红。

import XCTest
import CryptoKit
@testable import AgentLiteCore

final class CryptoTests: XCTestCase {

    // MARK: - 向量（来自 Java VectorGen，参数同 LanSync.java:64-67）

    private let accountID = "ios-cross-check-001"
    private let accountKey = "correct-horse-battery-staple"
    /// Java: Base64.getEncoder().encodeToString(PBKDF2WithHmacSHA256(...))
    /// 注意带 `=` 补位 —— 与 Java 标准 Base64 输出逐字符一致（不比对 URL-safe/无补位变体，
    /// 因为那会掩盖「字节数不同但去掉补位后看着一样」这类错误）。
    private let javaKeyB64 = "/ETJt9EdV1LbW1tGtRU82JtpGHkqJg6GY+qKNNL/A44="
    /// Java 固定 nonce=00..0b，明文见下，AAD = "agentlite-lan-v1|" + accountID
    private let javaEnvelope = "AAECAwQFBgcICQoLPy9LN8NLyAoLx8RidG6NRk7La8YSnV00yhMirZj8pHb89tuSE6Hoz8ypvZXff6+stvvkpCf+TMb8g546HPJxVAQIv88YHRzKbNIgHpTGiV6HmXcDXoRtmgDrhCvbUlZPo9p7"
    private let javaPlaintext = #"{"ts":1757690000,"device_id":"d_swift","since":7,"push":{"tasks":[],"memories":[]}}"#

    // MARK: - PBKDF2

    func testLANKeyDerivationMatchesJava() {
        let key = AgentLiteCrypto.deriveLANKey(accountKey: accountKey, accountID: accountID)
        let got = key.withUnsafeBytes { Data($0).base64EncodedString() }
        XCTAssertEqual(got, javaKeyB64, "PBKDF2 派生与 Java 不一致 —— 局域网密钥会两端不同")
    }

    func testLANKeyDerivationIsDeterministic() {
        let a = AgentLiteCrypto.deriveLANKey(accountKey: accountKey, accountID: accountID)
        let b = AgentLiteCrypto.deriveLANKey(accountKey: accountKey, accountID: accountID)
        XCTAssertEqual(a.withUnsafeBytes { Data($0) }, b.withUnsafeBytes { Data($0) })
    }

    func testDifferentAccountIDsProduceDifferentKeys() {
        let a = AgentLiteCrypto.deriveLANKey(accountKey: accountKey, accountID: "acct-a")
        let b = AgentLiteCrypto.deriveLANKey(accountKey: accountKey, accountID: "acct-b")
        XCTAssertNotEqual(a.withUnsafeBytes { Data($0) }, b.withUnsafeBytes { Data($0) },
                          "salt 含 account_id，不同账号必须派生出不同密钥")
    }

    func testConstantsMatchProtocol() {
        // 这三个是跨端硬常量，改动即破坏互操作。写死在测试里，防止「顺手改一下」。
        XCTAssertEqual(AgentLiteCrypto.lanSaltPrefix, "agentlite-lan|")
        XCTAssertEqual(AgentLiteCrypto.lanIterations, 100_000)
        XCTAssertEqual(AgentLiteCrypto.aadPrefix, "agentlite-lan-v1|")
        XCTAssertEqual(AgentLiteCrypto.nonceLength, 12)
    }

    // MARK: - AES-256-GCM

    /// Swift 必须能解开 **Java 封的信** —— 这是「iOS 能参与局域网」的充分证明。
    func testOpenJavaEnvelope() throws {
        let key = AgentLiteCrypto.deriveLANKey(accountKey: accountKey, accountID: accountID)
        let plain = try AgentLiteCrypto.open(
            key: key,
            aad: AgentLiteCrypto.aadPrefix + accountID,
            envelope: javaEnvelope
        )
        XCTAssertEqual(plain, javaPlaintext)
    }

    /// 反向：Swift 封的信，用 Java 的密文逐字节比对。
    /// 密文可比是因为 nonce 固定 —— 生产路径的 nonce 是随机的。
    func testSealFromJavaNonceRoundTrips() throws {
        let key = AgentLiteCrypto.deriveLANKey(accountKey: accountKey, accountID: accountID)
        let combined = Data(base64Encoded: javaEnvelope)!
        let nonce = try AES.GCM.Nonce(data: combined.prefix(12))
        let sealed = try AES.GCM.seal(
            Data(javaPlaintext.utf8),
            using: key,
            nonce: nonce,
            authenticating: Data((AgentLiteCrypto.aadPrefix + accountID).utf8)
        )
        XCTAssertEqual(sealed.combined!.base64EncodedString(), javaEnvelope)
    }

    func testSealOpenRoundTrip() throws {
        let key = AgentLiteCrypto.deriveLANKey(accountKey: accountKey, accountID: accountID)
        let aad = AgentLiteCrypto.aadPrefix + accountID
        let envelope = try AgentLiteCrypto.seal(key: key, aad: aad, plaintext: javaPlaintext)
        let back = try AgentLiteCrypto.open(key: key, aad: aad, envelope: envelope)
        XCTAssertEqual(back, javaPlaintext)
    }

    /// AAD 不匹配必须失败。这条守的是「来源绑定」：AAD 绑了 account_id，
    /// 别的账号即使拿到密文也解不开。
    func testWrongAADFails() throws {
        let key = AgentLiteCrypto.deriveLANKey(accountKey: accountKey, accountID: accountID)
        let envelope = try AgentLiteCrypto.seal(key: key, aad: AgentLiteCrypto.aadPrefix + accountID, plaintext: "x")
        XCTAssertThrowsError(try AgentLiteCrypto.open(
            key: key,
            aad: AgentLiteCrypto.aadPrefix + "other-account",
            envelope: envelope
        ))
    }

    func testWrongKeyFails() throws {
        let k1 = AgentLiteCrypto.deriveLANKey(accountKey: "key-one-aaaaaaaa", accountID: accountID)
        let k2 = AgentLiteCrypto.deriveLANKey(accountKey: "key-two-bbbbbbbb", accountID: accountID)
        let env = try AgentLiteCrypto.seal(key: k1, aad: "aad", plaintext: "secret")
        XCTAssertThrowsError(try AgentLiteCrypto.open(key: k2, aad: "aad", envelope: env))
    }

    func testTamperedCiphertextFails() throws {
        let key = AgentLiteCrypto.deriveLANKey(accountKey: accountKey, accountID: accountID)
        let env = try AgentLiteCrypto.seal(key: key, aad: "aad", plaintext: "secret payload")
        var bytes = Array(Data(base64Encoded: env)!)
        bytes[bytes.count - 1] ^= 0xFF          // 动最后一个字节（落在 GCM tag 上）
        let tampered = Data(bytes).base64EncodedString()
        XCTAssertThrowsError(try AgentLiteCrypto.open(key: key, aad: "aad", envelope: tampered),
                             "篡改密文必须被 GCM tag 检出")
    }

    func testInvalidEnvelopeRejected() {
        let key = AgentLiteCrypto.deriveLANKey(accountKey: accountKey, accountID: accountID)
        XCTAssertThrowsError(try AgentLiteCrypto.open(key: key, aad: "aad", envelope: "AAAA"))
        XCTAssertThrowsError(try AgentLiteCrypto.open(key: key, aad: "aad", envelope: "not base64!!!"))
    }

    func testNonceExtraction() throws {
        let key = AgentLiteCrypto.deriveLANKey(accountKey: accountKey, accountID: accountID)
        let env = try AgentLiteCrypto.seal(key: key, aad: "aad", plaintext: "x")
        let nonce = AgentLiteCrypto.nonceB64(ofEnvelope: env)
        XCTAssertNotNil(nonce)
        XCTAssertEqual(Data(base64Encoded: nonce!)!.count, 12)
        // 抗重放缓存按 nonce 去重，两封信的 nonce 必须不同
        let env2 = try AgentLiteCrypto.seal(key: key, aad: "aad", plaintext: "x")
        XCTAssertNotEqual(nonce, AgentLiteCrypto.nonceB64(ofEnvelope: env2))
    }

    func testRandomHexLengthAndUniqueness() {
        let a = AgentLiteCrypto.randomHex(bytes: 16)
        let b = AgentLiteCrypto.randomHex(bytes: 16)
        XCTAssertEqual(a.count, 32)
        XCTAssertNotEqual(a, b)
    }
}
