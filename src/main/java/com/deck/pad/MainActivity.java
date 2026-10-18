package com.deck.pad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Deck: a customizable button panel that controls a Windows PC.
 * Buttons, pages and icons come from deck_server.py on the PC; edits made
 * here are sent back and saved there.
 */
public class MainActivity extends Activity {

    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;
    private static final int C_BG = 0xFF111111;
    private static final int C_BAR = 0xFF1C1C1C;
    private static final int C_GRAY = 0xFF9E9E9E;
    private static final int C_GREEN = 0xFF4CAF50;
    private static final int C_RED = 0xFFE53935;
    private static final int C_TEXT = 0xFFEEEEEE;

    private static final String[] TYPES = {"open", "exe", "cmd", "keys", "brightness", "app"};
    private static final String[] TYPE_LABELS = {
            "فتح رابط / تطبيق", "تشغيل برنامج (مسار)", "أمر (cmd)", "اختصار كيبورد", "سطوع الشاشة",
            "برنامج مثبّت (اختيار من القائمة)"};
    private static final String[] VALUE_HINTS = {
            "https://...  أو  steam://...", "C:\\Program Files\\...\\app.exe", "start \"\" chrome",
            "ctrl+k", "+10  أو  -10  أو  50", "اضغط زر الاختيار فوق"};
    private static final String[] VALUE_HELP = {
            "رابط موقع مثل youtube.com (بينفتح بكروم وأيقونته بتنزل لحالها)، أو بروتوكول تطبيق مثل steam:// ، أو مسار ملف أو مجلد.",
            "المسار الكامل لملف exe.",
            "أمر يتنفّذ عبر cmd.",
            "اختصارات مفصولة بمسافة، مثل: ctrl+k  أو  space  أو  ctrl+shift+z  أو  f5  أو  volume_up.\n"
                    + "المفتاح + اكتبه plus. الاختصار بينضغط على البرنامج المفتوح بالكمبيوتر.",
            "رقم من 0 لـ 100، أو +10 / -10 للزيادة والنقصان (بتشتغل على شاشات اللابتوب).",
            "اختر أي برنامج من قائمة الكمبيوتر، حتى برامج متجر مايكروسوفت. الأيقونة بتنزل لحالها."};
    private static final String[] SWATCHES = {
            "#1a73e8", "#2a475e", "#107c10", "#d97757", "#0078d4", "#6b5b95",
            "#b00020", "#b8860b", "#4a4a4a", "#00897b", "#e91e63", "#455a64"};

    private SharedPreferences prefs;
    private TextView status;
    private LinearLayout tabs;
    private LinearLayout grid;
    private JSONObject cfg;
    private int page = 0;

    private static final int PORT = 8765;
    private final Object lock = new Object();
    private volatile String lastGood = "";

    private final Handler beat = new Handler();
    private volatile boolean resumed = false;
    private volatile boolean beating = false;
    private volatile boolean offline = false;
    private volatile int misses = 0;
    private WifiManager.WifiLock wifiLock;

    private final Runnable beatTask = new Runnable() {
        @Override
        public void run() {
            if (!resumed) {
                return;
            }
            if (!beating && prefs.getString("key", "").length() > 0) {
                beating = true;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            heartbeat();
                        } finally {
                            beating = false;
                        }
                    }
                }).start();
            }
            beat.postDelayed(this, 5000);
        }
    };

    private final ExecutorService iconPool = Executors.newFixedThreadPool(2);
    private final Map<String, Bitmap> memIcons = new ConcurrentHashMap<String, Bitmap>();
    private final Set<String> failedIcons = Collections.synchronizedSet(new HashSet<String>());

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        System.setProperty("http.keepAlive", "false");
        prefs = getSharedPreferences("deck", MODE_PRIVATE);
        page = prefs.getInt("page", 0);
        lastGood = prefs.getString("last", "");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(neonBackground());
        root.addView(buildBar(), new LinearLayout.LayoutParams(MATCH, WRAP));

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setBackgroundColor(0x00000000);
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(6), 0, dp(6), dp(6));
        tabScroll.addView(tabs, new FrameLayout.LayoutParams(WRAP, WRAP));
        root.addView(tabScroll, new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(4), dp(4), dp(4), dp(4));
        sv.addView(grid, new FrameLayout.LayoutParams(MATCH, WRAP));
        root.addView(sv, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        setContentView(root);

        String cached = prefs.getString("cfg", null);
        if (cached != null) {
            try {
                cfg = new JSONObject(cached);
            } catch (JSONException e) {
                cfg = null;
            }
        }
        render();

        if (prefs.getString("key", "").length() == 0) {
            openSettings();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        try {
            WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            if (wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "deck");
            }
            if (!wifiLock.isHeld()) {
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
        if (prefs.getString("key", "").length() > 0) {
            refresh();
        }
        beat.removeCallbacks(beatTask);
        beat.postDelayed(beatTask, 5000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        beat.removeCallbacks(beatTask);
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Exception ignored) {
        }
    }

    /** Runs every 5 s: keeps the WiFi link busy, notices drops, and finds the PC again. */
    private void heartbeat() {
        boolean ok = lastGood.length() > 0 && ping(lastGood, 2500);
        if (!ok) {
            misses++;
            if (misses >= 2 && (misses - 2) % 3 == 0) {
                ok = findHost() != null;
            }
        }
        if (ok) {
            misses = 0;
            if (offline) {
                offline = false;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refresh();
                    }
                });
            }
        } else if (misses >= 2 && !offline) {
            offline = true;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setStatus(2, "غير متصل - عم دوّر على الكمبيوتر");
                }
            });
        }
    }

    // ------------------------------------------------------------------ bar

    private View buildBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0x33000000);
        bar.setPadding(dp(14), dp(2), dp(2), dp(2));

        TextView title = new TextView(this);
        title.setText("Deck");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(null, Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(WRAP, WRAP));

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setPadding(dp(12), 0, dp(4), 0);
        bar.addView(status, new LinearLayout.LayoutParams(0, WRAP, 1f));
        setStatus(0, "");

        DotsView dots = new DotsView(this);
        dots.setBackground(stateBg(0x00000000, 0x33FFFFFF, 24));
        dots.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMenu(v);
            }
        });
        bar.addView(dots, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return bar;
    }

    private void openMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "تحديث");
        m.getMenu().add(0, 2, 1, "إضافة صفحة");
        m.getMenu().add(0, 3, 2, "تعديل هالصفحة");
        m.getMenu().add(0, 4, 3, "الإعدادات");
        m.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        refresh();
                        break;
                    case 2:
                        pageDialog(-1);
                        break;
                    case 3:
                        pageDialog(page);
                        break;
                    default:
                        openSettings();
                }
                return true;
            }
        });
        m.show();
    }

    /** state: 0 = neutral, 1 = connected, 2 = disconnected */
    private void setStatus(int state, String text) {
        status.setTextColor(state == 1 ? C_GREEN : (state == 2 ? C_RED : C_GRAY));
        status.setText(text.length() == 0 ? "" : "● " + text);
    }

    // --------------------------------------------------------------- render

    private JSONObject curPage() {
        JSONArray ps = cfg == null ? null : cfg.optJSONArray("pages");
        return ps == null ? null : ps.optJSONObject(page);
    }

    private void render() {
        tabs.removeAllViews();
        grid.removeAllViews();
        JSONArray pages = cfg == null ? null : cfg.optJSONArray("pages");
        if (pages == null || pages.length() == 0) {
            message(prefs.getString("key", "").length() == 0
                    ? "أهلاً! من القائمة (⋮) اختر \"الإعدادات\" واكتب الرمز اللي بيظهر بنافذة start.bat."
                    : "ما في أزرار بعد. تأكد إنك شغّلت start.bat على الكمبيوتر.");
            return;
        }
        if (page >= pages.length() || page < 0) {
            page = 0;
        }
        for (int i = 0; i < pages.length(); i++) {
            tabs.addView(makeTab(i, pages.optJSONObject(i).optString("name", "?")));
        }
        tabs.addView(makeTab(-1, "+"));

        JSONObject pg = pages.optJSONObject(page);
        int cols = Math.max(1, Math.min(6, pg.optInt("columns", 3)));
        JSONArray btns = pg.optJSONArray("buttons");
        int n = btns == null ? 0 : btns.length();
        int total = n + 1; // the last tile is "+"
        int rows = (total + cols - 1) / cols;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(row, new LinearLayout.LayoutParams(MATCH, dp(96), 1f));
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, MATCH, 1f);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                if (i < n) {
                    row.addView(makeTile(page, i, btns.optJSONObject(i)), lp);
                } else if (i == n) {
                    row.addView(makeAddTile(), lp);
                } else {
                    row.addView(new View(this), lp);
                }
            }
        }
    }

    private void message(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_GRAY);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(24), dp(48), dp(24), dp(24));
        grid.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private View makeTab(final int idx, String name) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        boolean sel = idx == page;
        t.setTextColor(sel ? Color.WHITE : C_GRAY);
        t.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
        t.setBackground(sel ? glassBg(0xFF00F0FF, 18) : stateBg(0x00000000, 0x22FFFFFF, 18));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (idx < 0) {
                    pageDialog(-1);
                } else {
                    page = idx;
                    prefs.edit().putInt("page", page).apply();
                    render();
                }
            }
        });
        if (idx >= 0) {
            t.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    pageDialog(idx);
                    return true;
                }
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.setMargins(dp(2), dp(2), dp(2), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private View makeTile(final int p, final int i, JSONObject b) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(4), dp(6), dp(4), dp(6));
        int color = parseColor(b.optString("color", ""));
        t.setBackground(glassBg(color, 18));

        String ik = b.optString("_ik", "");
        final ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(44), dp(44));
        ilp.bottomMargin = dp(4);
        t.addView(iv, ilp);
        iv.setVisibility(View.GONE);

        TextView tv = new TextView(this);
        tv.setText(b.optString("label", "?"));
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, ik.length() > 0 ? 13 : 18);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setShadowLayer(dp(6), 0, 0, neon(color));
        tv.setMaxLines(2);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(tv, new LinearLayout.LayoutParams(MATCH, WRAP));

        if (ik.length() > 0) {
            loadIcon(ik, p, i, iv);
        }

        final View tile = t;
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                press(p, i, tile);
            }
        });
        t.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                tileMenu(i);
                return true;
            }
        });
        return t;
    }

    private View makeAddTile() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x14FFFFFF);
        d.setCornerRadius(dp(18));
        d.setStroke(dp(2), 0x8800F0FF, dp(6), dp(4));
        TextView t = new TextView(this);
        t.setText("+");
        t.setTextColor(0xCC00F0FF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        t.setGravity(Gravity.CENTER);
        t.setBackground(d);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editButton(-1);
            }
        });
        return t;
    }

    // ---------------------------------------------------------------- icons

    private void loadIcon(final String ik, final int p, final int i, final ImageView iv) {
        iv.setTag(ik);
        Bitmap cached = memIcons.get(ik);
        if (cached != null) {
            setIcon(iv, cached);
            return;
        }
        if (failedIcons.contains(ik)) {
            return;
        }
        iv.setVisibility(View.INVISIBLE); // reserve the space while loading
        iconPool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bm = null;
                File f = new File(getCacheDir(), "ic_" + ik + ".png");
                try {
                    if (f.exists()) {
                        bm = BitmapFactory.decodeFile(f.getPath());
                    }
                    if (bm == null) {
                        byte[] data = httpBytes("GET", "/api/icon?p=" + p + "&i=" + i, null);
                        bm = BitmapFactory.decodeByteArray(data, 0, data.length);
                        if (bm != null) {
                            FileOutputStream o = new FileOutputStream(f);
                            o.write(data);
                            o.close();
                        }
                    }
                } catch (Exception e) {
                    bm = null;
                }
                final Bitmap result = bm;
                if (result != null) {
                    memIcons.put(ik, result);
                } else {
                    failedIcons.add(ik);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!ik.equals(iv.getTag())) {
                            return;
                        }
                        if (result != null) {
                            setIcon(iv, result);
                        } else {
                            iv.setVisibility(View.GONE);
                        }
                    }
                });
            }
        });
    }

    private void setIcon(ImageView iv, Bitmap bm) {
        BitmapDrawable d = new BitmapDrawable(getResources(), bm);
        d.setFilterBitmap(true);
        iv.setImageDrawable(d);
        iv.setVisibility(View.VISIBLE);
    }

    // -------------------------------------------------------------- network

    private void refresh() {
        failedIcons.clear();
        setStatus(0, "جاري الاتصال...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final String text = new String(httpBytes("GET", "/api/config", null), "UTF-8");
                    final JSONObject fresh = new JSONObject(text);
                    prefs.edit().putString("cfg", text).apply();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            cfg = fresh;
                            offline = false;
                            misses = 0;
                            render();
                            setStatus(1, "متصل " + lastGood.replace(":" + PORT, ""));
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus(2, "غير متصل");
                            if (cfg == null) {
                                grid.removeAllViews();
                                message(e.getMessage()
                                        + "\n\n• شغّل start.bat على الكمبيوتر\n"
                                        + "• نفس الـ WiFi، أو وصّل كابل USB وفعّل USB Tethering\n"
                                        + "• تأكد من الرمز بالإعدادات");
                            } else {
                                toast(e.getMessage());
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private void press(final int p, final int i, final View v) {
        v.setAlpha(0.5f);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    httpBytes("POST", "/api/press?p=" + p + "&i=" + i, null);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String error = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        v.setAlpha(1f);
                        if (error != null) {
                            setStatus(2, "فشل");
                            Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                        } else {
                            setStatus(1, "متصل");
                        }
                    }
                });
            }
        }).start();
    }

    /** Sends the whole config to the PC, then reloads it (to get fresh icon keys). */
    private void saveCfg() {
        final String text = cfg.toString();
        prefs.edit().putString("cfg", text).apply();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    httpBytes("POST", "/api/config", text.getBytes(Charset.forName("UTF-8")));
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            refresh();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setStatus(2, "ما انحفظ");
                            toast("ما انحفظ على الكمبيوتر: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    /** Server answered with an error (wrong code, bad button...). Not a network problem. */
    private static class HttpFail extends Exception {
        HttpFail(String msg) {
            super(msg);
        }
    }

    /**
     * Talks to the PC. The PC is found automatically (WiFi or USB tethering) and
     * searched for again if the link drops or its address changes.
     */
    private byte[] httpBytes(String method, String path, byte[] body) throws Exception {
        String host = lastGood;
        if (host.length() == 0) {
            host = findHost();
            if (host == null) {
                throw new Exception("ما لقيت الكمبيوتر. شغّل start.bat وتأكد من الـ WiFi أو كابل USB (Tethering)");
            }
        }
        try {
            return request(host, method, path, body);
        } catch (IOException first) {
            if ("GET".equals(method)) {
                try {
                    Thread.sleep(300);
                    return request(host, method, path, body);
                } catch (IOException again) {
                    // fall through to the search below
                } catch (InterruptedException ie) {
                    // fall through
                }
            }
            // A press must not run twice: only retry when the connection never opened.
            boolean neverConnected = first instanceof ConnectException
                    || first instanceof NoRouteToHostException
                    || first instanceof UnknownHostException;
            if (!"GET".equals(method) && !neverConnected) {
                throw new Exception("انقطع الاتصال بالكمبيوتر");
            }
            lastGood = "";
            String again = findHost();
            if (again == null) {
                throw new Exception("تعذّر الاتصال بالكمبيوتر");
            }
            try {
                return request(again, method, path, body);
            } catch (IOException second) {
                throw new Exception("تعذّر الاتصال بالكمبيوتر");
            }
        }
    }

    private byte[] request(String host, String method, String path, byte[] body)
            throws IOException, HttpFail {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + host + path).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(15000);
            c.setRequestMethod(method);
            c.setRequestProperty("X-Deck-Key", prefs.getString("key", ""));
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(body.length);
            } else if ("POST".equals(method)) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
            }
            try {
                c.connect();
            } catch (SocketTimeoutException e) {
                throw new ConnectException("connect timeout");
            }
            if (body != null) {
                OutputStream o = c.getOutputStream();
                o.write(body);
                o.close();
            } else if ("POST".equals(method)) {
                c.getOutputStream().close();
            }
            int code = c.getResponseCode();
            byte[] data = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                String msg = new String(data, "UTF-8");
                try {
                    msg = new JSONObject(msg).optString("msg", msg);
                } catch (Exception ignored) {
                }
                throw new HttpFail(msg.length() == 0 ? "خطأ " + code : msg);
            }
            return data;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** True if a Deck server answers at host:port. */
    private boolean ping(String hostPort, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + hostPort + "/api/ping").openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs + 700);
            if (c.getResponseCode() != 200) {
                return false;
            }
            return new String(readAll(c.getInputStream()), "UTF-8").contains("\"deck\"");
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private List<String> manualHosts() {
        List<String> out = new ArrayList<String>();
        for (String h : prefs.getString("host", "").split("[\\s,;]+")) {
            if (h.length() > 0 && !h.equalsIgnoreCase("auto")) {
                out.add(h.indexOf(':') < 0 ? h + ":" + PORT : h);
            }
        }
        return out;
    }

    private String remember(String hostPort) {
        lastGood = hostPort;
        prefs.edit().putString("last", hostPort).apply();
        return hostPort;
    }

    /** Looks for the PC: the last address, typed addresses, then every nearby address. */
    private String findHost() {
        synchronized (lock) {
            if (lastGood.length() > 0 && ping(lastGood, 1500)) {
                return lastGood;
            }
            lastGood = "";
            for (String h : manualHosts()) {
                if (ping(h, 1200)) {
                    return remember(h);
                }
            }
            String found = scan();
            return found == null ? null : remember(found);
        }
    }

    /** Tries all 254 addresses of each network this phone is on (WiFi, USB tethering...). */
    private String scan() {
        Set<String> targets = new LinkedHashSet<String>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = ni.getName().toLowerCase();
                if (!ni.isUp() || ni.isLoopback() || name.startsWith("rmnet") || name.startsWith("ccmni")
                        || name.startsWith("pdp") || name.startsWith("tun") || name.startsWith("dummy")) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address)) {
                        continue;
                    }
                    byte[] b = a.getAddress();
                    String base = (b[0] & 255) + "." + (b[1] & 255) + "." + (b[2] & 255) + ".";
                    for (int i = 1; i < 255; i++) {
                        if (i != (b[3] & 255)) {
                            targets.add(base + i + ":" + PORT);
                        }
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        if (targets.isEmpty()) {
            return null;
        }
        ExecutorService ex = Executors.newFixedThreadPool(48);
        CompletionService<String> cs = new ExecutorCompletionService<String>(ex);
        for (final String t : targets) {
            cs.submit(new Callable<String>() {
                @Override
                public String call() {
                    return ping(t, 450) ? t : null;
                }
            });
        }
        String found = null;
        try {
            for (int i = 0; i < targets.size() && found == null; i++) {
                found = cs.take().get();
            }
        } catch (Exception ignored) {
        }
        ex.shutdownNow();
        return found;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
        }
        return out.toByteArray();
    }

    // -------------------------------------------------------------- dialogs

    private AlertDialog.Builder builder() {
        return new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK);
    }

    private void confirm(String msg, final Runnable onYes) {
        builder().setMessage(msg)
                .setPositiveButton("نعم", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        onYes.run();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void openSettings() {
        LinearLayout form = form();
        final EditText host = field("فاضي = بحث تلقائي", prefs.getString("host", ""));
        final EditText key = field("الرمز (6 أحرف)", prefs.getString("key", ""));
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        form.addView(label("الرمز"));
        form.addView(key);
        form.addView(help("بيظهر بنافذة start.bat على الكمبيوتر."));
        form.addView(label("عنوان الكمبيوتر (اختياري)"));
        form.addView(host);
        form.addView(help("اتركه فاضي: التطبيق بيلاقي الكمبيوتر لحاله عبر الـ WiFi أو كابل USB، "
                + "وبيرجع يدوّر عليه إذا تغيّر العنوان. للـ USB: وصّل الكابل وفعّل USB Tethering "
                + "من إعدادات الموبايل. وإذا بدك تحدد عنوان يدوياً، اكتبه هون (ممكن أكتر من عنوان)."));

        builder().setTitle("الاتصال بالكمبيوتر")
                .setView(scroll(form))
                .setPositiveButton("حفظ", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String h = host.getText().toString().trim()
                                .replace("http://", "").replace("https://", "");
                        prefs.edit().putString("host", h)
                                .putString("key", key.getText().toString().trim().toUpperCase())
                                .remove("last")
                                .apply();
                        lastGood = "";
                        refresh();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void tileMenu(final int idx) {
        builder().setItems(new CharSequence[]{"تعديل", "نقل للأمام", "نقل للخلف", "حذف"},
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            editButton(idx);
                        } else if (which == 1) {
                            moveButton(idx, -1);
                        } else if (which == 2) {
                            moveButton(idx, 1);
                        } else {
                            confirm("حذف هالزر؟", new Runnable() {
                                @Override
                                public void run() {
                                    deleteButton(idx);
                                }
                            });
                        }
                    }
                }).show();
    }

    private void moveButton(int idx, int dir) {
        try {
            JSONObject pg = curPage();
            JSONArray arr = pg.getJSONArray("buttons");
            int j = idx + dir;
            if (j < 0 || j >= arr.length()) {
                return;
            }
            pg.put("buttons", swap(arr, idx, j));
            render();
            saveCfg();
        } catch (JSONException e) {
            toast("خطأ بالبيانات");
        }
    }

    private void deleteButton(int idx) {
        try {
            JSONObject pg = curPage();
            pg.put("buttons", removeAt(pg.getJSONArray("buttons"), idx));
            render();
            saveCfg();
        } catch (JSONException e) {
            toast("خطأ بالبيانات");
        }
    }

    /** idx = -1 creates a new button on the current page. */
    private void editButton(final int idx) {
        final JSONObject pg = curPage();
        if (pg == null) {
            toast("اتصل بالكمبيوتر أول");
            return;
        }
        final JSONArray arr;
        JSONObject existing = null;
        try {
            arr = pg.getJSONArray("buttons");
            if (idx >= 0) {
                existing = new JSONObject(arr.getJSONObject(idx).toString());
            }
        } catch (JSONException e) {
            toast("خطأ بالبيانات");
            return;
        }
        final boolean isNew = existing == null;
        final JSONObject b = isNew ? new JSONObject() : existing;

        int typeIdx = 0;
        boolean known = true;
        String curType = b.optString("type", "");
        if (curType.length() > 0) {
            known = false;
            for (int i = 0; i < TYPES.length; i++) {
                if (TYPES[i].equals(curType)) {
                    typeIdx = i;
                    known = true;
                }
            }
        }
        final boolean knownType = known;

        LinearLayout form = form();
        final EditText fLabel = field("اسم الزر", b.optString("label", ""));
        final EditText fColor = field("#RRGGBB", b.optString("color", "#455a64"));
        form.addView(label("الاسم"));
        form.addView(fLabel);

        form.addView(label("اللون"));
        form.addView(fColor);
        HorizontalScrollView sw = new HorizontalScrollView(this);
        sw.setHorizontalScrollBarEnabled(false);
        LinearLayout swRow = new LinearLayout(this);
        swRow.setOrientation(LinearLayout.HORIZONTAL);
        for (final String hex : SWATCHES) {
            View s = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(Color.parseColor(hex));
            g.setCornerRadius(dp(18));
            s.setBackground(g);
            s.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    fColor.setText(hex);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
            lp.setMargins(0, dp(6), dp(8), dp(6));
            swRow.addView(s, lp);
        }
        sw.addView(swRow);
        form.addView(sw);

        final Spinner spinner = new Spinner(this);
        final EditText fValue = field("", "");
        final EditText fWindow = field("مثال: Premiere  (اختياري)", b.optString("window", ""));
        final TextView valueHelp = help("");
        final String[] pickedName = {b.optString("appname", "")};
        final Button pickBtn = new Button(this);
        pickBtn.setText("اختر من البرامج المثبتة");
        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickApp(new AppPick() {
                    @Override
                    public void picked(String name, String id) {
                        fValue.setText(id);
                        pickedName[0] = name;
                        if (fLabel.getText().toString().trim().length() == 0) {
                            fLabel.setText(name);
                        }
                    }
                });
            }
        });
        final TextView windowLabel = label("ركّز على نافذة قبل الضغط (اختياري)");

        if (knownType) {
            ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                    android.R.layout.simple_spinner_item, TYPE_LABELS);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(ad);
            spinner.setSelection(typeIdx);
            fValue.setText(valueOf(b, typeIdx));
            form.addView(label("نوع الإجراء"));
            form.addView(spinner);
            form.addView(pickBtn);
            form.addView(label("القيمة"));
            form.addView(fValue);
            form.addView(valueHelp);
            form.addView(windowLabel);
            form.addView(fWindow);
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    fValue.setHint(VALUE_HINTS[pos]);
                    valueHelp.setText(VALUE_HELP[pos]);
                    int vis = pos == 3 ? View.VISIBLE : View.GONE;
                    fWindow.setVisibility(vis);
                    windowLabel.setVisibility(vis);
                    pickBtn.setVisibility(pos == 5 ? View.VISIBLE : View.GONE);
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            fValue.setHint(VALUE_HINTS[typeIdx]);
            valueHelp.setText(VALUE_HELP[typeIdx]);
            int vis = typeIdx == 3 ? View.VISIBLE : View.GONE;
            fWindow.setVisibility(vis);
            windowLabel.setVisibility(vis);
            pickBtn.setVisibility(typeIdx == 5 ? View.VISIBLE : View.GONE);
        } else {
            form.addView(help("هاد الزر من نوع \"" + curType
                    + "\" (ماكرو). الإجراء بيتعدّل من ملف buttons.json على الكمبيوتر، وهون بتقدر تغيّر الاسم واللون والأيقونة."));
        }

        final EditText fIcon = field("اتركه فاضي = تلقائي", b.optString("icon", ""));
        form.addView(label("الأيقونة (اختياري)"));
        form.addView(fIcon);
        form.addView(help("فاضي = أيقونة تلقائية (للروابط بتنزل أيقونة الموقع). أمثلة: app:chrome ، app:steam ، "
                + "app:xbox ، أو مسار exe، أو صورة png بمجلد icons جنب السيرفر. "
                + "اكتب none لزر بدون أيقونة."));

        final AlertDialog dlg = builder()
                .setTitle(isNew ? "زر جديد" : "تعديل الزر")
                .setView(scroll(form))
                .setPositiveButton("حفظ", null)
                .setNegativeButton("إلغاء", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String lab = fLabel.getText().toString().trim();
                if (lab.length() == 0) {
                    toast("اكتب اسم للزر");
                    return;
                }
                String col = fColor.getText().toString().trim();
                if (!col.startsWith("#")) {
                    col = "#" + col;
                }
                try {
                    Color.parseColor(col);
                } catch (IllegalArgumentException e) {
                    toast("اللون غلط. مثال: #1a73e8");
                    return;
                }
                try {
                    if (knownType) {
                        String val = fValue.getText().toString().trim();
                        if (val.length() == 0) {
                            toast("اكتب القيمة");
                            return;
                        }
                        if (!applyAction(b, spinner.getSelectedItemPosition(), val,
                                fWindow.getText().toString().trim())) {
                            toast("للسطوع اكتب رقم، مثل 50 أو +10 أو -10");
                            return;
                        }
                        if (spinner.getSelectedItemPosition() == 5) {
                            b.put("appname", pickedName[0]);
                        }
                    }
                    b.put("label", lab);
                    b.put("color", col);
                    String icon = fIcon.getText().toString().trim();
                    if (icon.length() > 0) {
                        b.put("icon", icon);
                    } else {
                        b.remove("icon");
                    }
                    b.remove("_ik");
                    if (isNew) {
                        arr.put(b);
                    } else {
                        arr.put(idx, b);
                    }
                } catch (JSONException e) {
                    toast("خطأ بالبيانات");
                    return;
                }
                dlg.dismiss();
                render();
                saveCfg();
            }
        });
    }

    /** idx = -1 creates a new page. */
    private void pageDialog(final int idx) {
        if (cfg == null) {
            toast("اتصل بالكمبيوتر أول");
            return;
        }
        final JSONArray pages = cfg.optJSONArray("pages");
        final JSONObject pg = idx >= 0 ? pages.optJSONObject(idx) : null;

        LinearLayout form = form();
        final EditText fName = field("اسم الصفحة", pg == null ? "" : pg.optString("name", ""));
        final EditText fCols = field("3", pg == null ? "3" : String.valueOf(pg.optInt("columns", 3)));
        fCols.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(label("الاسم"));
        form.addView(fName);
        form.addView(label("عدد الأعمدة (1 - 6)"));
        form.addView(fCols);

        AlertDialog.Builder bd = builder()
                .setTitle(pg == null ? "صفحة جديدة" : "تعديل الصفحة")
                .setView(scroll(form))
                .setPositiveButton("حفظ", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String name = fName.getText().toString().trim();
                        if (name.length() == 0) {
                            name = "صفحة";
                        }
                        int cols = 3;
                        try {
                            cols = Integer.parseInt(fCols.getText().toString().trim());
                        } catch (NumberFormatException ignored) {
                        }
                        cols = Math.max(1, Math.min(6, cols));
                        try {
                            if (pg == null) {
                                JSONObject np = new JSONObject();
                                np.put("name", name);
                                np.put("columns", cols);
                                np.put("buttons", new JSONArray());
                                pages.put(np);
                                page = pages.length() - 1;
                                prefs.edit().putInt("page", page).apply();
                            } else {
                                pg.put("name", name);
                                pg.put("columns", cols);
                            }
                        } catch (JSONException e) {
                            toast("خطأ بالبيانات");
                            return;
                        }
                        render();
                        saveCfg();
                    }
                })
                .setNegativeButton("إلغاء", null);
        if (pg != null && pages.length() > 1) {
            bd.setNeutralButton("حذف الصفحة", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    confirm("حذف الصفحة وكل أزرارها؟", new Runnable() {
                        @Override
                        public void run() {
                            try {
                                cfg.put("pages", removeAt(pages, idx));
                                page = 0;
                                prefs.edit().putInt("page", 0).apply();
                                render();
                                saveCfg();
                            } catch (JSONException e) {
                                toast("خطأ بالبيانات");
                            }
                        }
                    });
                }
            });
        }
        bd.show();
    }

    // ---------------------------------------------------------- app picker

    private interface AppPick {
        void picked(String name, String id);
    }

    private void pickApp(final AppPick cb) {
        toast("جاري تحميل قائمة البرامج...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String txt = new String(httpBytes("GET", "/api/apps", null), "UTF-8");
                    final JSONArray arr = new JSONArray(txt);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showAppList(arr, cb);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast("ما قدرت جيب البرامج: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void showAppList(JSONArray arr, final AppPick cb) {
        final List<String> names = new ArrayList<String>();
        final List<String> ids = new ArrayList<String>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) {
                names.add(o.optString("n"));
                ids.add(o.optString("id"));
            }
        }
        final List<Integer> shown = new ArrayList<Integer>();
        final List<String> labels = new ArrayList<String>();
        final ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, labels);
        final EditText search = field("ابحث...", "");
        final Runnable fill = new Runnable() {
            @Override
            public void run() {
                String qs = search.getText().toString().trim().toLowerCase();
                shown.clear();
                labels.clear();
                for (int i = 0; i < names.size(); i++) {
                    if (qs.length() == 0 || names.get(i).toLowerCase().contains(qs)) {
                        shown.add(i);
                        labels.add(names.get(i));
                    }
                }
                ad.notifyDataSetChanged();
            }
        };
        fill.run();
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                fill.run();
            }
        });
        ListView lv = new ListView(this);
        lv.setAdapter(ad);
        LinearLayout box = form();
        box.addView(search);
        box.addView(lv, new LinearLayout.LayoutParams(MATCH, dp(320)));
        final AlertDialog dlg = builder().setTitle("اختر برنامج")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .create();
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                int k = shown.get(pos);
                dlg.dismiss();
                cb.picked(names.get(k), ids.get(k));
            }
        });
        dlg.show();
    }

    // ------------------------------------------------------- action <-> form

    private static String valueOf(JSONObject b, int t) {
        switch (t) {
            case 0:
                return b.optString("target", "");
            case 1:
                return b.optString("path", "");
            case 2:
                return b.optString("command", "");
            case 3:
                return b.optString("keys", "");
            case 5:
                return b.optString("appid", "");
            default:
                if (b.has("delta")) {
                    int d = b.optInt("delta");
                    return d > 0 ? "+" + d : String.valueOf(d);
                }
                return b.has("value") ? String.valueOf(b.optInt("value")) : "";
        }
    }

    private static boolean applyAction(JSONObject b, int t, String v, String window)
            throws JSONException {
        b.remove("target");
        b.remove("path");
        b.remove("command");
        b.remove("keys");
        b.remove("delta");
        b.remove("value");
        b.remove("window");
        b.remove("appid");
        b.remove("appname");
        b.put("type", TYPES[t]);
        switch (t) {
            case 0:
                b.put("target", v);
                break;
            case 1:
                b.put("path", v);
                break;
            case 2:
                b.put("command", v);
                break;
            case 3:
                b.put("keys", v);
                if (window.length() > 0) {
                    b.put("window", window);
                }
                break;
            case 5:
                b.put("appid", v);
                break;
            default:
                try {
                    int n = Integer.parseInt(v.replace("+", "").trim());
                    if (v.startsWith("+") || v.startsWith("-")) {
                        b.put("delta", n);
                    } else {
                        b.put("value", n);
                    }
                } catch (NumberFormatException e) {
                    return false;
                }
        }
        return true;
    }

    // -------------------------------------------------------------- helpers

    private static JSONArray removeAt(JSONArray a, int idx) throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            if (i != idx) {
                out.put(a.get(i));
            }
        }
        return out;
    }

    private static JSONArray swap(JSONArray a, int i, int j) throws JSONException {
        JSONArray out = new JSONArray();
        for (int k = 0; k < a.length(); k++) {
            out.put(a.get(k == i ? j : (k == j ? i : k)));
        }
        return out;
    }

    private LinearLayout form() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(16), dp(8), dp(16), dp(8));
        return f;
    }

    private ScrollView scroll(View child) {
        ScrollView s = new ScrollView(this);
        s.addView(child);
        return s;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(null, Typeface.BOLD);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private TextView help(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(C_GRAY);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    private EditText field(String hint, String text) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(text);
        e.setSingleLine(true);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0xFF777777);
        return e;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private static int parseColor(String s) {
        try {
            return Color.parseColor(s);
        } catch (Exception e) {
            return 0xFF444444;
        }
    }

    private static int darker(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= 0.7f;
        return Color.HSVToColor(hsv);
    }

    private static int neon(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(hsv[1], 0.55f);
        hsv[2] = Math.max(hsv[2], 0.95f);
        return Color.HSVToColor(hsv);
    }

    private static int withAlpha(int color, int a) {
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** One "liquid glass" layer: tinted translucent body, neon edge and a soft white sheen on top. */
    private android.graphics.drawable.Drawable glassLayer(int color, boolean pressed, int radiusDp) {
        int c = neon(color);
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{withAlpha(c, pressed ? 0xAA : 0x66), withAlpha(c, pressed ? 0x66 : 0x22)});
        base.setCornerRadius(dp(radiusDp));
        base.setStroke(dp(pressed ? 2 : 1), withAlpha(c, pressed ? 0xFF : 0xCC));
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x55FFFFFF, 0x10FFFFFF, 0x00FFFFFF});
        sheen.setCornerRadius(dp(radiusDp));
        LayerDrawable ld = new LayerDrawable(new android.graphics.drawable.Drawable[]{base, sheen});
        ld.setLayerInset(1, dp(2), dp(2), dp(2), dp(2));
        return ld;
    }

    private StateListDrawable glassBg(int color, int radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, glassLayer(color, true, radiusDp));
        s.addState(new int[]{}, glassLayer(color, false, radiusDp));
        return s;
    }

    private android.graphics.drawable.Drawable neonBackground() {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF070914, 0xFF0B0F24, 0xFF120A22});
        GradientDrawable a = new GradientDrawable();
        a.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        a.setGradientRadius(dp(280));
        a.setGradientCenter(0.1f, 0.05f);
        a.setColors(new int[]{0x4400F0FF, 0x0000F0FF});
        GradientDrawable b = new GradientDrawable();
        b.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        b.setGradientRadius(dp(300));
        b.setGradientCenter(0.95f, 0.95f);
        b.setColors(new int[]{0x44FF28C8, 0x00FF28C8});
        return new LayerDrawable(new android.graphics.drawable.Drawable[]{base, a, b});
    }

    private StateListDrawable stateBg(int normal, int pressed, int radiusDp) {
        GradientDrawable n = new GradientDrawable();
        n.setColor(normal);
        n.setCornerRadius(dp(radiusDp));
        GradientDrawable p = new GradientDrawable();
        p.setColor(pressed);
        p.setCornerRadius(dp(radiusDp));
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, p);
        s.addState(new int[]{}, n);
        return s;
    }

    /** Three vertical dots, drawn by hand so no icon file is needed. */
    private static class DotsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        DotsView(Context c) {
            super(c);
            paint.setColor(0xFFDDDDDD);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = getWidth() / 16f;
            for (int k = -1; k <= 1; k++) {
                canvas.drawCircle(cx, cy + k * r * 3.4f, r, paint);
            }
        }
    }
}
