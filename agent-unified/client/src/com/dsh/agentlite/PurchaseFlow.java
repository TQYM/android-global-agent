// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 充值流程的判定逻辑——**纯函数，零 android / org.json 依赖**。
 *
 * <p>抽出来的理由：这段逻辑决定了「用户的钱到没到账」这个判断，一旦判错，
 * 要么让用户以为充值成功却用不了（假阴性被当成功），要么在用户明明付了款时
 * 反复催他重付（假阳性被当失败）。这种逻辑必须能在桌面 JVM 上用固定向量反复回归，
 * 而不是只能装机手点（见 {@code tests/PurchaseFlowTest.java}）。
 *
 * <p>设计口径来自 `PURCHASE_REDIRECT_DESIGN` §5.3 缺口 1 的决策：
 * **不猜时间窗**，以官方侧的真实差异为准。
 *
 * <p><b>安全取向：宁可判「没新增」也不判「已付款」。</b>
 * 假阳性（把"切出去看了眼微信"当成付款成功）会让流程继续往前走、
 * 去创建一把用不上的 Key；假阴性只是让用户手动点一下重新检查。
 * 两者代价不对等，所以所有模糊地带一律偏向保守。
 */
public final class PurchaseFlow {

    private PurchaseFlow() {}

    /** 判定结果。 */
    public enum Verdict {
        /** 官方侧确实新增了 Key → 可以继续创建/采用流程。 */
        NEW_KEY_FOUND,
        /** 没有新增（用户还没付款，或付款了但系统还没落账）。 */
        NO_NEW_KEY,
        /** 快照缺失（没记进入前状态）→ 无法比对，必须重取快照，禁止据此推进。 */
        NO_BASELINE
    }

    /** 判定结论 + 供界面展示的原因。 */
    public static final class Decision {
        public final Verdict verdict;
        public final KeyInfo key;
        public final String reason;

        Decision(Verdict verdict, KeyInfo key, String reason) {
            this.verdict = verdict;
            this.key = key;
            this.reason = reason;
        }

        public boolean canProceed() { return verdict == Verdict.NEW_KEY_FOUND; }
    }

    /** 一把已存在的 Key 的摘要（**不含 Key 明文**，比对差异性用不到它）。 */
    public static final class KeyInfo {
        public final long apiKeyId;
        public final long gmtCreate;
        public final String description;
        public final boolean disabled;

        public KeyInfo(long apiKeyId, long gmtCreate, String description, boolean disabled) {
            this.apiKeyId = apiKeyId;
            this.gmtCreate = gmtCreate;
            this.description = description == null ? "" : description;
            this.disabled = disabled;
        }
    }

    /** 进入购买页前的基线快照。 */
    public static final class Snapshot {
        public final Set<Long> ids;
        public final long takenAtMs;

        public Snapshot(Collection<KeyInfo> keys, long takenAtMs) {
            Set<Long> s = new HashSet<>();
            if (keys != null) {
                for (KeyInfo k : keys) {
                    if (k != null && k.apiKeyId != 0) s.add(k.apiKeyId);
                }
            }
            this.ids = Collections.unmodifiableSet(s);
            this.takenAtMs = takenAtMs;
        }
    }

    /** 时钟偏差宽限：客户端与阿里云服务端的时间不可能完全一致，留足余量。 */
    public static final long CLOCK_GRACE_MS = 60_000L;

    /**
     * 比对前后差异。
     *
     * <p>两步判定：
     * <ol>
     *   <li><b>id 集合新增</b>（首选）—— id 由服务端分配、全局唯一，最可靠。
     *       新增多个时取 `gmtCreate` 最新的那个（用户可能连点两次）。</li>
     *   <li><b>退回 `gmtCreate` 比较</b>（仅在 id 全不可用时）——与快照时刻比较，
     *       并**减去 {@link #CLOCK_GRACE_MS} 宽限**。宽限方向是刻意选的：
     *       它让"刚好卡在边界"的 Key 被判为新增（因为 takenAt 被推早了），
     *       但由于 id 路径已覆盖绝大多数情况，这里更该防的是**时钟漂移导致的误判**。</li>
     * </ol>
     */
    public static Decision evaluate(Snapshot before, List<KeyInfo> after) {
        if (before == null) {
            return new Decision(Verdict.NO_BASELINE, null,
                    "缺少进入购买页前的记录，无法判断是否新增——请重新检查");
        }
        if (after == null || after.isEmpty()) {
            return new Decision(Verdict.NO_NEW_KEY, null,
                    "账号下没有 API Key（可能尚未完成购买）");
        }

        // 1) id 集合差异
        KeyInfo newest = null;
        boolean anyNew = false;
        for (KeyInfo k : after) {
            if (k == null || k.apiKeyId == 0) continue;
            if (!before.ids.contains(k.apiKeyId)) {
                anyNew = true;
                if (newest == null || k.gmtCreate > newest.gmtCreate) newest = k;
            }
        }
        if (anyNew) {
            return new Decision(Verdict.NEW_KEY_FOUND, newest,
                    "检测到新创建的 API Key（id=" + newest.apiKeyId + "）");
        }

        // 2) 兜底：id 不可用时按创建时间判断
        boolean allIdsMissing = true;
        for (KeyInfo k : after) {
            if (k != null && k.apiKeyId != 0) { allIdsMissing = false; break; }
        }
        if (allIdsMissing) {
            long threshold = before.takenAtMs - CLOCK_GRACE_MS;
            KeyInfo best = null;
            for (KeyInfo k : after) {
                if (k == null || k.gmtCreate <= 0) continue;
                if (k.gmtCreate > threshold && (best == null || k.gmtCreate > best.gmtCreate)) {
                    best = k;
                }
            }
            if (best != null) {
                return new Decision(Verdict.NEW_KEY_FOUND, best,
                        "按创建时间判断为新增（gmtCreate=" + best.gmtCreate + "）");
            }
        }

        return new Decision(Verdict.NO_NEW_KEY, null,
                "未检测到新建的 API Key——若已付款，请稍等片刻后重试");
    }
}
