package de.harryjane.growtagebuch;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private WebView webView;
    private SensorDbHelper db;
    private static final int REQ_PERMS = 5179;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new SensorDbHelper(this);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new GrowBridge(this), "AndroidGrowBridge");
        webView.loadUrl("file:///android_asset/index.html");
    }

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBluetoothPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            p.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), REQ_PERMS);
        else startScanService();
    }

    private void startScanService() {
        Intent i = new Intent(MainActivity.this, H5179ScanService.class);
        i.setAction(H5179ScanService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            boolean ok = grantResults.length > 0;
            for (int r : grantResults) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok || hasBluetoothPermission()) startScanService();
        }
    }

    @Override public void onBackPressed() {
        if (webView == null) { super.onBackPressed(); return; }
        webView.evaluateJavascript(
            "(window.GrowAppBack ? String(window.GrowAppBack()) : 'false')",
            result -> {
                boolean handled = result != null && result.contains("true");
                if (!handled) MainActivity.super.onBackPressed();
            }
        );
    }

    public class GrowBridge {
        private final Context context;
        GrowBridge(Context c) { context = c; }

        @JavascriptInterface public void requestPermissions() {
            runOnUiThread(() -> requestBluetoothPermissions());
        }

        @JavascriptInterface public void startH5179Scan() {
            runOnUiThread(() -> {
                if (hasBluetoothPermission()) startScanService(); else requestBluetoothPermissions();
            });
        }

        @JavascriptInterface public void stopH5179Scan() {
            stopService(new Intent(MainActivity.this, H5179ScanService.class));
        }

        @JavascriptInterface public String getLatestReading() {
            SharedPreferences p = getSharedPreferences(H5179ScanService.PREFS, MODE_PRIVATE);
            try {
                JSONObject o = new JSONObject();
                o.put("temperature", Double.longBitsToDouble(p.getLong("temp_bits", Double.doubleToLongBits(Double.NaN))));
                o.put("humidity", Double.longBitsToDouble(p.getLong("hum_bits", Double.doubleToLongBits(Double.NaN))));
                o.put("battery", p.getInt("battery", -1));
                o.put("rssi", p.getInt("rssi", 0));
                o.put("name", p.getString("name", "Govee H5179"));
                o.put("address", p.getString("address", ""));
                o.put("environment", p.getString("environment", ""));
                o.put("ts", p.getLong("ts", 0));
                return o.toString();
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface public String getHistory(long since, int limit) {
            return db.queryJson(since, limit);
        }

        @JavascriptInterface public void clearHistory() {
            db.clear();
            getSharedPreferences(H5179ScanService.PREFS, MODE_PRIVATE).edit().putLong("last_db_ts", 0).apply();
        }

        @JavascriptInterface public void setEnvironment(String name) {
            setSensorSettings(name, 60, true);
        }

        @JavascriptInterface public void setSensorSettings(String name, int intervalSeconds, boolean recording) {
            getSharedPreferences(H5179ScanService.PREFS, MODE_PRIVATE).edit()
                    .putString("environment", name == null ? "Grow-Tagebuch" : name)
                    .putInt("interval_seconds", Math.max(10, Math.min(intervalSeconds, 3600)))
                    .putBoolean("recording", recording)
                    .apply();
            ClimateWidgetProvider.updateAll(context);
        }

        @JavascriptInterface public String getPlatformInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("native", true);
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("permissionGranted", hasBluetoothPermission());
                BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
                BluetoothAdapter ba = bm == null ? null : bm.getAdapter();
                boolean enabled = false;
                try { enabled = ba != null && ba.isEnabled(); } catch (SecurityException ignored) {}
                o.put("bluetoothEnabled", enabled);
                boolean pinSupported = Build.VERSION.SDK_INT >= 26 && AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported();
                o.put("widgetPinSupported", pinSupported);
                o.put("historyCount", db.count());
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public void requestPinWidget() {
            if (Build.VERSION.SDK_INT < 26) return;
            runOnUiThread(() -> {
                AppWidgetManager mgr = AppWidgetManager.getInstance(context);
                if (!mgr.isRequestPinAppWidgetSupported()) return;
                ComponentName provider = new ComponentName(context, ClimateWidgetProvider.class);
                Intent success = new Intent(context, MainActivity.class);
                PendingIntent cb = PendingIntent.getActivity(context, 5179, success,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                mgr.requestPinAppWidget(provider, null, cb);
            });
        }
    }
}
