package de.harryjane.h5179test;

import android.Manifest;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 5179;

    private TextView statusView;
    private TextView sensorView;
    private TextView tempView;
    private TextView humidityView;
    private TextView batteryView;
    private TextView rssiView;
    private TextView updatedView;
    private TextView historyView;

    private final BroadcastReceiver readingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshFromStorage();
            String status = intent.getStringExtra(H5179ScanService.EXTRA_STATUS);
            if (status != null) statusView.setText(status);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshFromStorage();
        statusView.setText(hasBlePermissions() ? "Bereit. Tippe auf Sensor starten." : "Bluetooth-Berechtigung wird beim Start angefragt.");
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(H5179ScanService.ACTION_READING);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(readingReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(readingReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { unregisterReceiver(readingReceiver); } catch (Exception ignored) { }
    }

    private View buildUi() {
        int pad = dp(18);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(241, 246, 239));
        scroll.addView(root);

        TextView title = text("Grow-Tagebuch · H5179 Test", 24, true);
        title.setTextColor(Color.rgb(32, 73, 48));
        root.addView(title);
        root.addView(text("Test 1: BLE-Livewerte · Test 2: Hintergrund · Test 3: Widget", 14, false));

        statusView = text("", 15, true);
        statusView.setPadding(0, dp(18), 0, dp(12));
        root.addView(statusView);

        sensorView = valueRow(root, "Sensor");
        tempView = valueRow(root, "Temperatur");
        humidityView = valueRow(root, "Luftfeuchte");
        batteryView = valueRow(root, "Batterie");
        rssiView = valueRow(root, "Signal");
        updatedView = valueRow(root, "Letztes Signal");

        Button start = button("H5179-Sensor starten");
        start.setOnClickListener(v -> startSensorWithPermission());
        root.addView(start);

        Button stop = button("Sensor stoppen");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, H5179ScanService.class));
            statusView.setText("Sensor-Scan gestoppt.");
        });
        root.addView(stop);

        Button widget = button("Klima-Widget hinzufügen");
        widget.setOnClickListener(v -> requestWidget());
        root.addView(widget);

        Button bluetooth = button("Bluetooth-Einstellungen öffnen");
        bluetooth.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        root.addView(bluetooth);

        TextView histTitle = text("Letzte Messungen", 18, true);
        histTitle.setPadding(0, dp(18), 0, dp(6));
        root.addView(histTitle);
        historyView = text("Noch keine Messungen.", 14, false);
        historyView.setTextIsSelectable(true);
        root.addView(historyView);

        TextView note = text("Hinweis: Dies ist bewusst eine kleine native Test-App. Wenn Sensor, Hintergrundaufzeichnung und Widget funktionieren, übernehmen wir dieselbe Technik in die eigentliche Grow-Tagebuch-App.", 13, false);
        note.setPadding(0, dp(18), 0, dp(24));
        root.addView(note);
        return scroll;
    }

    private TextView valueRow(LinearLayout root, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(7), 0, dp(7));
        TextView l = text(label, 15, true);
        TextView v = text("—", 17, true);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        v.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(l);
        row.addView(v);
        root.addView(row);
        return v;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(35, 45, 38));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(8);
        b.setLayoutParams(p);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void startSensorWithPermission() {
        if (!hasBlePermissions()) {
            requestBlePermissions();
            return;
        }
        requestNotificationPermissionIfNeeded();
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            statusView.setText("Dieses Handy unterstützt kein Bluetooth LE.");
            return;
        }
        if (!adapter.isEnabled()) {
            statusView.setText("Bitte Bluetooth einschalten und danach erneut auf Start tippen.");
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }
        Intent service = new Intent(this, H5179ScanService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        statusView.setText("Aktiver BLE-Scan läuft … warte auf H5179-Messpaket.");
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBlePermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSIONS);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMISSIONS + 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (hasBlePermissions()) startSensorWithPermission();
            else statusView.setText("Bluetooth-Berechtigung fehlt. Ohne sie kann der H5179 nicht gelesen werden.");
        }
    }

    private void refreshFromStorage() {
        SharedPreferences p = getSharedPreferences(H5179ScanService.PREFS, MODE_PRIVATE);
        boolean has = p.contains("temperature");
        sensorView.setText(p.getString("sensorName", "—"));
        tempView.setText(has ? String.format(Locale.GERMANY, "%.2f °C", p.getFloat("temperature", 0f)) : "—");
        humidityView.setText(has ? String.format(Locale.GERMANY, "%.2f %%", p.getFloat("humidity", 0f)) : "—");
        batteryView.setText(has ? p.getInt("battery", -1) + " %" : "—");
        rssiView.setText(has ? p.getInt("rssi", 0) + " dBm" : "—");
        long ts = p.getLong("timestamp", 0L);
        updatedView.setText(ts > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale.GERMANY).format(new Date(ts)) : "—");
        String history = p.getString("history", "");
        historyView.setText(history.isEmpty() ? "Noch keine Messungen." : history);
    }

    private void requestWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        ComponentName provider = new ComponentName(this, ClimateWidgetProvider.class);
        if (Build.VERSION.SDK_INT >= 26 && manager.isRequestPinAppWidgetSupported()) {
            boolean requested = manager.requestPinAppWidget(provider, null, null);
            if (!requested) Toast.makeText(this, "Launcher hat die Widget-Anfrage abgelehnt.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Startbildschirm lange drücken → Widgets → Grow H5179 Test", Toast.LENGTH_LONG).show();
        }
    }
}
