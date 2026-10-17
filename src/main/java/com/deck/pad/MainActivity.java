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
    private final Set<
