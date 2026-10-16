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
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    private static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;
    private static final int C_BG = 0xFF111111;
    private static final int C_BAR = 0xFF1C1C1C;
    private static final int C_GRAY = 0xFF9E9E9E;
    private static final int C_GREEN = 0xFF4CAF50;
    private static final int C_RED = 0xFFE53935;
    private static final int C_TEXT = 0xFFEEEEEE;

    private static final String[] TYPES = {"open", "exe", "cmd", "keys", "brightness"};
    private static final String[] TYPE_LABELS = {
            "فتح رابط / تطبيق", "تشغيل برنامج (مسار)", "أمر (cmd)", "اختصار كيبورد", "سطوع الشاشة"};
    private static final String[] VALUE_HINTS = {
            "https://...  أو  steam://...", "C:\\Program Files\\...\\app.exe", "start \"\" chrome",
            "ctrl+k", "+10  أو  -10  أو  50"};
    private static final String[] VALUE_HELP = {
            "رابط موقع، أو بروتوكول تطبيق مثل steam:// و xbox: ، أو مسار ملف أو مجلد.",
            "المسار الكامل لملف exe.",
            "أمر يتنفّذ عبر cmd.",
            "اختصارات مفصولة بمسافة، مثل: ctrl+k  أو  space  أو  ctrl+shift+z  أو  f5  أو  volume_up.\n"
                    + "المفتاح + اكتبه plus. الاختصار بينضغط على البرنامج المفتوح بالكمبيوتر.",
            "رقم من 0 لـ 100، أو +10 / -10 للزيادة والنقصان (بتشتغل على شاشات اللابتوب)."};
    private static final String[] SWATCHES = {
            "#1a73e8", "#2a475e", "#107c10", "#d97757", "#0078d4", "#6b5b95",
            "#b00020", "#b8860b", "#4a4a4a", "#00897b", "#e91e63", "#455a64"};

    private SharedPreferences prefs;
    private TextView status;
    private LinearLayout tabs;
    private LinearLayout grid;
    private JSONObject cfg;
    private int page = 0;

    private final ExecutorService iconPool = Executors.newFixedThreadPool(2);
    private final Map<String, Bitmap> memIcons = new ConcurrentHashMap<String, Bitmap>();
    private final Set<String> failedIcons = Collections.synchronizedSet(new HashSet<String>());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("deck", MODE_PRIVATE);
        page = prefs.getInt("page", 0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.addView(buildBar(), new LinearLayout.LayoutParams(MATCH, WRAP));

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setBackgroundColor(C_BAR);
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

        if (prefs.getString("host", "").length() == 0) {
            openSettings();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.getString("host", "").length() > 0) {
            refresh();
        }
    }

    private View buildBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(C_BAR);
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

    private void setStatus(int state, String text) {
        status.setTextColor(state == 1 ? C_GREEN : (state == 2 ? C_RED : C_GRAY));
        status.setText(text.length() == 0 ? "" : "● " + text);
    }

    private JSONObject curPage() {
        JSONArray ps = cfg == null ? null : cfg.optJSONArray("pages");
        return ps == null ? null : ps.optJSONObject(page);
    }

    private void render() {
        tabs.removeAllViews();
        grid.removeAllViews();
        JSONArray pages = cfg == null ? null : cfg.optJSONArray("pages");
        if (pages == null || pages.length() == 0) {
            message(prefs.getString("host", "").length() == 0
                    ? "أهلاً! من القائمة (⋮) اختر \"الإعدادات\" واكتب عنوان الكمبيوتر والرمز."
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
        int total = n + 1;
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
        t.setBackground(stateBg(sel ? 0xFF3A3A3A : 0x00000000, 0xFF2A2A2A, 18));
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
        t.setBackground(stateBg(color, darker(color), 14));

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
        d.setColor(0xFF1A1A1A);
        d.setCornerRadius(dp(14));
        d.setStroke(dp(2), 0xFF444444, dp(6), dp(4));
        TextView t = new TextView(this);
        t.setText("+");
        t.setTextColor(0xFF777777);
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
        iv.setVisibility(View.INVISIBLE);
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
                            render();
                            setStatus(1, "متصل");
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
                                        + "• نفس الـ WiFi\n• تأكد من العنوان والرمز بالإعدادات");
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

    private byte[] httpBytes(String method, String path, byte[] body) throws Exception {
        String host = prefs.getString("host", "");
        if (host.length() == 0) {
            throw new Exception("اكتب عنوان الكمبيوتر من الإعدادات");
        }
        if (host.indexOf(':') < 0) {
            host = host + ":8765";
        }
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
                OutputStream o = c.getOutputStream();
                o.write(body);
                o.close();
            } else if ("POST".equals(method)) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
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
                throw new Exception(msg.length() == 0 ? "خطأ " + code : msg);
            }
            return data;
        } catch (IOException e) {
            throw new Exception("تعذّر الاتصال بالكمبيوتر");
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
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
        final EditText host = field("مثال: 192.168.1.5", prefs.getString("host", ""));
        final EditText key = field("الرمز (6 أحرف)", prefs.getString("key", ""));
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        form.addView(label("عنوان الكمبيوتر"));
        form.addView(host);
        form.addView(label("الرمز"));
        form.addView(key);
        form.addView(help("الاتنين بيظهروا بنافذة start.bat على الكمبيوتر. الموبايل والكمبيوتر لازم يكونوا على نفس الـ WiFi."));

        builder().setTitle("الاتصال بالكمبيوتر")
                .setView(scroll(form))
                .setPositiveButton("حفظ", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String h = host.getText().toString().trim()
                                .replace("http://", "").replace("https://", "");
                        while (h.endsWith("/")) {
                            h = h.substring(0, h.length() - 1);
                        }
                        prefs.edit().putString("host", h)
                                .putString("key", key.getText().toString().trim().toUpperCase())
                                .apply();
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
        } else {
            form.addView(help("هاد الزر من نوع \"" + curType
                    + "\" (ماكرو). الإجراء بيتعدّل من ملف buttons.json على الكمبيوتر، وهون بتقدر تغيّر الاسم واللون والأيقونة."));
        }

        final EditText fIcon = field("اتركه فاضي = تلقائي", b.optString("icon", ""));
        form.addView(label("الأيقونة (اختياري)"));
        form.addView(fIcon);
        form.addView(help("أمثلة: app:chrome ، app:steam ، app:obs ، app:premiere ، app:davinci ، app:capcut ، "
                + "app:xbox ، app:store ، أو مسار exe، أو صورة png بمجلد icons جنب السيرفر. "
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
