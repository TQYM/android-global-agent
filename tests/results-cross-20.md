# 20 轮双端交叉测试结果（2026-09-15 21:44–22:26）

- 段通过 **25/40**（62%）；A 手机→手表 10/20；B 手表→手机 15/20；跨设备回环 0 次
- 步数/证据来源：设备 engine.log 按序回溯（`tests/backfill_cross_evidence.py`），新脚本已改为运行期原生采集

| 轮 | A 手机→手表 | A 指令 | A 手表步数 | B 手表→手机 | B 二跳指令 | B 手表转达步 | B 手机执行步 | 手机前台 |
|---|---|---|---|---|---|---|---|---|
| 1 | ✗  |  |  | ✓ | c_9feb599c8d703709 | 2 | 2 | com.android.settings |
| 2 | ✓ done | c_e572329304b65284 | 2 | ✓ | c_fc7af9c68b91771a | 2 | 4 | com.coloros.calculator |
| 3 | ✓ done | c_63b24234a831af54 | 3 | ✓ | c_ce35bbcba3beadde | 2 | 2 | com.oplus.wirelesssettings |
| 4 | ✗ failed | c_4006a160e3d5accf | 2 | ✓ | c_44c2ef858ee23319 | 4 | 2 | com.oplus.wirelesssettings |
| 5 | ✓ done | c_6c42dbe4d5d78f44 | 2 | ✓ | c_bd447717f194be97 | 2 | 2 | com.coloros.calculator |
| 6 | ✗  |  |  | ✓ | c_74d90e7f7798bc45 | 1 | 50 | com.android.settings |
| 7 | ✗ failed | c_a11360cebea04129 | 2 | ✓ | c_846d1c23f5d567c0 | 2 | 3 | com.oplus.battery |
| 8 | ✓ done | c_d66c4eceaea3e623 | 2 | ✓ | c_2c6cb228179ff8f8 | 8 | 2 | com.android.settings |
| 9 | ✗ failed | c_1808eff2956e059e | 2 | ✓ | c_781d5b143f03a428 | 1 | 2 | com.android.settings |
| 10 | ✓ done | c_bb93422f7693ce65 | 2 | ✗ |  |  |  |  |
| 11 | ✓ done | c_6964e822fce98971 | 2 | ✓ | c_2804aa92b130a706 | 2 | 3 | com.coloros.alarmclock |
| 12 | ✗  |  |  | ✗ | c_d18fc14aae080b89 | 6 | 12 | com.dsh.agentlite |
| 13 | ✓ done | c_f82aec3f9c1b0e85 | 16 | ✓ | c_ab2742476e573b36 | 2 | 3 | com.coloros.gallery3d |
| 14 | ✗ failed | c_b13bff10601c6e45 | 2 | ✗ | c_8187a0e425b0c475 | 1 | 2 | com.dsh.agentlite |
| 15 | ✓ done | c_4b9f242c1f1dfefb | 11 | ✓ | c_c9e614544ac26e3f | 12 | 1 | com.android.settings |
| 16 | ✓ done | c_f92183269dfb354e | 2 | ✓ | c_915d3dd64fb46300 | 2 | 3 | com.android.settings |
| 17 | ✗  |  |  | ✓ | c_6a1297c826872db6 | 2 | 2 | com.heytap.browser |
| 18 | ✗  |  |  | ✗ |  |  |  |  |
| 19 | ✗ failed | c_be714b627e5b46e9 | 2 | ✗ | c_4c78568a970ecf27 | 1 | 2 | com.dsh.agentlite |
| 20 | ✓ done | c_2e4b22b2da52eabf | 2 | ✓ | c_ca0d8c05d76caad6 | 13 | 2 | com.coloros.weather2 |

## 步数统计

- 手表执行步数：样本 15，中位 2，范围 2–16
- 手机执行步数：样本 18，中位 2，范围 1–50
