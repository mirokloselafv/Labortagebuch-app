package de.harryjane.growtagebuch;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.util.SparseArray;

import java.util.List;
import java.util.Locale;

public class H5179ScanService extends Service {
    public static final String ACTION_START = "de.harryjane.growtagebuch.START_H5179";
    public static final String ACTION_STOP = "de.harryjane.growtagebuch.STOP_H5179";
    public static final String PREFS = "h5179_native";
    private static final String CHANNEL = "h5179_scan";
    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private SensorDbHelper db;

    @Override public void onCreate() {
        super.onCreate();
        db = new SensorDbHelper(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "H5179 Sensor", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopScan(); stopForeground(true); stopSelf(); return START_NOT_STICKY;
        }
        startForeground(5179, buildNotification("Suche Govee H5179…"));
        startScan();
        return START_STICKY;
    }

    private android.app.Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(this, CHANNEL)
                : new android.app.Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Grow-Tagebuch · H5179")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private boolean hasScanPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    private void startScan() {
        if (scanning || !hasScanPermission()) return;
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            notifyText("Bluetooth nicht verfügbar");
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                notifyText("Bluetooth ist ausgeschaltet");
                return;
            }
        } catch (SecurityException e) {
            notifyText("Bluetooth-Berechtigung fehlt");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { notifyText("BLE-Scanner nicht verfügbar"); return; }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0)
                .build();
        try {
            scanner.startScan(null, settings, callback);
            scanning = true;
        } catch (SecurityException e) {
            notifyText("BLE-Scan nicht erlaubt");
        }
    }

    private void stopScan() {
        if (scanner != null && scanning && hasScanPermission()) {
            try { scanner.stopScan(callback); } catch (Exception ignored) {}
        }
        scanning = false;
    }

    private final ScanCallback callback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) { parseResult(result); }
        @Override public void onBatchScanResults(List<ScanResult> results) { for (ScanResult r : results) parseResult(r); }
        @Override public void onScanFailed(int errorCode) { notifyText("BLE-Scanfehler " + errorCode); }
    };

    private void parseResult(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record == null) return;
        String name = record.getDeviceName();
        if (name == null) name = "";
        String upper = name.toUpperCase(Locale.ROOT);
        SparseArray<byte[]> data = record.getManufacturerSpecificData();
        Reading reading = null;

        for (int i = 0; i < data.size(); i++) {
            int manufacturerId = data.keyAt(i);
            byte[] payload = data.valueAt(i);

            // Hardware-confirmed H5179 packet: MFG 0x8801, 9-byte payload. Temperature int16 LE @4/5, RH uint16 LE @6/7, battery @8.
            if ((manufacturerId == 0x8801 || upper.contains("H5179")) && payload != null && payload.length >= 9) {
                reading = parseH5179Classic(payload);
                if (reading != null) break;
            }

            // GV5179: packed temp/humidity in payload bytes 2..4, battery/error in byte 5.
            if (upper.contains("GV5179") && payload != null && payload.length >= 6) {
                reading = parseGV5179(payload);
                if (reading != null) break;
            }
        }
        if (reading == null) return;

        reading.rssi = result.getRssi();
        reading.name = name.isEmpty() ? "Govee H5179" : name;
        try { reading.address = result.getDevice().getAddress(); }
        catch (SecurityException e) { reading.address = ""; }
        save(reading);
    }

    private Reading parseH5179Classic(byte[] b) {
        if (b == null || b.length < 9) return null;
        int rawT = (b[4] & 0xff) | ((b[5] & 0xff) << 8);
        if ((rawT & 0x8000) != 0) rawT -= 0x10000;
        int rawH = (b[6] & 0xff) | ((b[7] & 0xff) << 8);
        double t = rawT / 100.0;
        double h = rawH / 100.0;
        int batt = b[8] & 0xff;
        if (t < -40 || t > 100 || h < 0 || h > 100 || batt > 100) return null;
        return new Reading(t, h, batt);
    }

    private Reading parseGV5179(byte[] b) {
        if (b == null || b.length < 6) return null;
        int base = ((b[2] & 0xff) << 16) | ((b[3] & 0xff) << 8) | (b[4] & 0xff);
        boolean neg = (base & 0x800000) != 0;
        int v = base & 0x7fffff;
        double t = (v / 1000) / 10.0;
        if (neg) t = -t;
        double h = (v % 1000) / 10.0;
        int last = b[5] & 0xff;
        int batt = last & 0x7f;
        boolean err = (last & 0x80) != 0;
        if (err || t < -40 || t > 100 || h < 0 || h > 100 || batt > 100) return null;
        return new Reading(t, h, batt);
    }

    private void save(Reading r) {
        long now = System.currentTimeMillis();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String env = prefs.getString("environment", "Grow-Tagebuch");
        prefs.edit()
                .putLong("temp_bits", Double.doubleToLongBits(r.temp))
                .putLong("hum_bits", Double.doubleToLongBits(r.hum))
                .putInt("battery", r.battery)
                .putInt("rssi", r.rssi)
                .putString("name", r.name)
                .putString("address", r.address)
                .putLong("ts", now)
                .apply();

        boolean recording = prefs.getBoolean("recording", true);
        int interval = Math.max(10, prefs.getInt("interval_seconds", 60));
        long lastDb = prefs.getLong("last_db_ts", 0);
        if (recording && (lastDb == 0 || now - lastDb >= interval * 1000L)) {
            db.insert(now, r.temp, r.hum, r.battery, r.rssi, r.name, r.address, env);
            prefs.edit().putLong("last_db_ts", now).apply();
        }

        notifyText(String.format(Locale.GERMANY, "%.1f °C · %.0f %% rF", r.temp, r.hum));
        ClimateWidgetProvider.updateAll(this);
    }

    private void notifyText(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(5179, buildNotification(text));
    }

    @Override public void onDestroy() { stopScan(); if (db != null) db.close(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }

    static class Reading {
        double temp, hum;
        int battery, rssi;
        String name = "", address = "";
        Reading(double t, double h, int b) { temp = t; hum = h; battery = b; }
    }
}
