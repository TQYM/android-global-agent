package dev.patrickgold.florisboard.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.patrickgold.florisboard.FlorisImeService;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 缝合桥：FlorisBoard IME + Agent 注入通道 + 拼音中文输入模式。
 *
 * - 广播 com.dsh.agent.IME_COMMIT（extras: text/replace）→ commitText（需 INJECT 权限）
 * - 默认英文模式 = 原生 FlorisBoard 界面，顶部细条「中」按钮切到拼音模式
 * - 拼音模式 = 本类自绘的纯 Java 键盘（候选条 + 字母键），词典读 assets/agent_pinyin.txt
 */
public class AgentImeBridge extends FlorisImeService {
    public static final String ACTION_COMMIT = "com.dsh.agent.IME_COMMIT";
    private static AgentImeBridge sInstance;

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String text = i.getStringExtra("text");
            if (text == null) return;
            boolean ok = commitText(text, i.getBooleanExtra("replace", false));
            logf("rx [" + text + "] ok=" + ok);
        }
    };

    // ---------- 生命周期（类/方法 final 已在 dex 层解锁） ----------

    @Override public void onCreate() {
        super.onCreate();
        sInstance = this;
        zhMode = getSharedPreferences("agent_bridge", MODE_PRIVATE).getBoolean("zhMode", false);
        registerReceiver(rx, new IntentFilter(ACTION_COMMIT),
                "dev.patrickgold.florisboard.agent.INJECT", null, Context.RECEIVER_EXPORTED);
        logf("onCreate " + getClass().getName());
    }

    @Override public void onDestroy() {
        sInstance = null;
        try { unregisterReceiver(rx); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override public View onCreateInputView() {
        View floris = super.onCreateInputView();
        enWrap = wrapEn(floris);
        return zhMode ? zhView() : enWrap;
    }

    // ---------- 静态注入通道 ----------

    public static boolean commitText(String text, boolean replace) {
        AgentImeBridge s = sInstance;
        if (s == null) return false;
        InputConnection ic = s.getCurrentInputConnection();
        if (ic == null) return false;
        if (replace) ic.performContextMenuAction(16908319); // selectAll
        return ic.commitText(text, 1);
    }

    void logf(String m) {
        try {
            File f = new File(getExternalFilesDir(null), "bridge.log");
            FileWriter w = new FileWriter(f, true);
            w.write(System.currentTimeMillis() + " " + m + "\n");
            w.close();
        } catch (Exception ignored) { }
    }

    // ---------- 拼音中文模式 ----------

    private boolean zhMode;
    private View enWrap;
    private View zhView;
    private final StringBuilder buf = new StringBuilder();
    private LinearLayout candBox;
    private TextView composingView;
    private PinyinEngine engine;

    private View wrapEn(View floris) {
        LinearLayout strip = new LinearLayout(this);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(16, 2, 16, 2);
        strip.setBackgroundColor(0xFF1C1F26);
        strip.addView(mkBtn("中", 0xFF2B3040, v -> setZhMode(true)));
        TextView tip = new TextView(this);
        tip.setText("  FlorisBoard · 点「中」输入中文");
        tip.setTextSize(10f);
        tip.setTextColor(0xFF6B7280);
        strip.addView(tip);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(strip, new LinearLayout.LayoutParams(-1, dp(26)));
        wrap.addView(floris);
        return wrap;
    }

    private void setZhMode(boolean zh) {
        zhMode = zh;
        getSharedPreferences("agent_bridge", MODE_PRIVATE).edit().putBoolean("zhMode", zh).apply();
        buf.setLength(0);
        setInputView(zh ? zhView() : enWrap);
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    private Button mkBtn(String label, int bg, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(0xFFE7EAF3);
        b.setTextSize(14f);
        b.setBackgroundColor(bg);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setOnClickListener(l);
        return b;
    }

    private View zhView() {
        if (zhView == null) {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(0xFF14171E);

            // 候选条：拼音缓冲 + 候选字/词
            LinearLayout candRow = new LinearLayout(this);
            candRow.setGravity(Gravity.CENTER_VERTICAL);
            candRow.setBackgroundColor(0xFF1C1F26);
            composingView = new TextView(this);
            composingView.setTextColor(0xFF58A6FF);
            composingView.setTextSize(14f);
            composingView.setPadding(dp(8), 0, dp(8), 0);
            candRow.addView(composingView);
            HorizontalScrollView hsv = new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            candBox = new LinearLayout(this);
            hsv.addView(candBox);
            candRow.addView(hsv, new LinearLayout.LayoutParams(0, -2, 1f));
            root.addView(candRow, new LinearLayout.LayoutParams(-1, dp(40)));

            String[][] rows = {
                    {"q","w","e","r","t","y","u","i","o","p"},
                    {"a","s","d","f","g","h","j","k","l"},
                    {"z","x","c","v","b","n","m"},
            };
            for (String[] row : rows) {
                LinearLayout lr = new LinearLayout(this);
                for (String k : row) {
                    Button b = mkBtn(k, 0xFF262C3A, v -> {
                        buf.append(((Button) v).getText());
                        refreshCandidates();
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
                    lp.setMargins(dp(2), dp(3), dp(2), dp(3));
                    lr.addView(b, lp);
                }
                root.addView(lr);
            }

            LinearLayout bottom = new LinearLayout(this);
            bottom.addView(mkBtn("EN", 0xFF1C1F26, v -> setZhMode(false)),
                    new LinearLayout.LayoutParams(0, dp(46), 1.4f));
            bottom.addView(mkBtn("，", 0xFF262C3A, v -> commitRaw("，")),
                    new LinearLayout.LayoutParams(0, dp(46), 1f));
            bottom.addView(mkBtn("空格", 0xFF262C3A, v -> pickCandidate(0)),
                    new LinearLayout.LayoutParams(0, dp(46), 2.6f));
            bottom.addView(mkBtn("。", 0xFF262C3A, v -> commitRaw("。")),
                    new LinearLayout.LayoutParams(0, dp(46), 1f));
            bottom.addView(mkBtn("⌫", 0xFF262C3A, v -> backspace()),
                    new LinearLayout.LayoutParams(0, dp(46), 1.4f));
            bottom.addView(mkBtn("↵", 0xFF262C3A, v -> enter()),
                    new LinearLayout.LayoutParams(0, dp(46), 1.4f));
            root.addView(bottom);
            zhView = root;
        }
        refreshCandidates();
        return zhView;
    }

    private void refreshCandidates() {
        if (candBox == null) return;
        composingView.setText(buf.length() == 0 ? "" : buf.toString());
        candBox.removeAllViews();
        if (buf.length() == 0) return;
        if (engine == null) engine = new PinyinEngine(this);
        List<String> cands = engine.candidates(buf.toString(), 14);
        for (int i = 0; i < cands.size(); i++) {
            final int idx = i;
            candBox.addView(mkBtn(cands.get(i), 0xFF1C1F26, v -> pickCandidate(idx)));
        }
    }

    private void pickCandidate(int idx) {
        if (engine == null) return;
        List<String> cands = engine.candidates(buf.toString(), 14);
        if (cands.isEmpty()) { commitRaw(buf.toString()); }
        else { if (idx >= cands.size()) idx = 0; commitRaw(cands.get(idx)); }
        buf.setLength(0);
        refreshCandidates();
    }

    private void backspace() {
        if (buf.length() > 0) {
            buf.setLength(buf.length() - 1);
            refreshCandidates();
        } else {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.deleteSurroundingText(1, 0);
        }
    }

    private void enter() {
        if (buf.length() > 0) {
            commitRaw(buf.toString());
            buf.setLength(0);
            refreshCandidates();
        } else {
            commitRaw("\n");
        }
    }

    private void commitRaw(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(s, 1);
    }

    // ---------- 极简拼音引擎 ----------

    static class PinyinEngine {
        private final HashMap<String, String> charMap = new HashMap<>();           // 音节 → 频率字串
        private final HashMap<String, List<String[]>> wordMap = new HashMap<>();   // 首音节 → [链, 词]

        PinyinEngine(Context ctx) {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        ctx.getAssets().open("agent_pinyin.txt"), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\t");
                    if (p.length == 2) {
                        charMap.put(p[0], p[1]);
                    } else if (p.length == 3 && "w".equals(p[0])) {
                        String[] chain = p[1].split(" ");
                        if (chain.length == 0) continue;
                        List<String[]> l = wordMap.get(chain[0]);
                        if (l == null) { l = new ArrayList<>(); wordMap.put(chain[0], l); }
                        l.add(new String[]{p[1], p[2]});
                    }
                }
                r.close();
            } catch (Exception ignored) { }
        }

        /** 贪心最长音节切分（charMap 的键即合法音节表） */
        List<String> split(String s) {
            List<String> out = new ArrayList<>();
            int i = 0;
            while (i < s.length()) {
                int best = 0;
                for (int len = Math.min(6, s.length() - i); len >= 1; len--) {
                    if (charMap.containsKey(s.substring(i, i + len))) { best = len; break; }
                }
                if (best == 0) { out.add(s.substring(i, i + 1)); i++; }
                else { out.add(s.substring(i, i + best)); i += best; }
            }
            return out;
        }

        List<String> candidates(String typed, int limit) {
            List<String> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            List<String> chain = split(typed);
            if (chain.isEmpty()) return out;
            // 1) 词候选：前缀链匹配（最后一个音节允许前缀匹配）
            List<String[]> words = wordMap.get(chain.get(0));
            if (words != null) {
                for (String[] w : words) {
                    String[] wc = w[0].split(" ");
                    if (wc.length < chain.size()) continue;
                    boolean ok = true;
                    for (int i = 0; i < chain.size(); i++) {
                        if (i == chain.size() - 1) {
                            if (!wc[i].startsWith(chain.get(i))) { ok = false; break; }
                        } else if (!wc[i].equals(chain.get(i))) { ok = false; break; }
                    }
                    if (ok && seen.add(w[1])) out.add(w[1]);
                    if (out.size() >= limit) return out;
                }
            }
            // 2) 单字候选：首音节精确
            String chars = charMap.get(chain.get(0));
            if (chars != null) {
                for (int i = 0; i < chars.length() && out.size() < limit; i++) {
                    String c = String.valueOf(chars.charAt(i));
                    if (seen.add(c)) out.add(c);
                }
            }
            // 3) 首音节为不完整输入时：按音节前缀给模糊字候选
            if (out.size() < limit && !charMap.containsKey(chain.get(0))) {
                for (String syl : charMap.keySet()) {
                    if (!syl.startsWith(chain.get(0))) continue;
                    String cs = charMap.get(syl);
                    for (int i = 0; i < Math.min(4, cs.length()) && out.size() < limit; i++) {
                        String c = String.valueOf(cs.charAt(i));
                        if (seen.add(c)) out.add(c);
                    }
                    if (out.size() >= limit) break;
                }
            }
            return out;
        }
    }
}
