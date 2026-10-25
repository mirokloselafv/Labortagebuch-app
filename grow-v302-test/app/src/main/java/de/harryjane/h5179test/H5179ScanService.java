package de.harryjane.h5179test;

import android.Manifest;
import android.app.Notification;
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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.SparseArray;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public class H5179ScanService extends Service {
    public static final String PREFS = "h5179_readings";
    public static final String ACTION_READING = "de.harryjane.h5179test.READING";
    public static final String EXTRA_STATUS = "status";

    private static final String CHANNEL_ID = "h5179_scan";
    private static final int NOTIFICATION_ID = 5179;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private long lastSavedAt;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForegroundCompat(buildNotification("Warte auf H5179 …"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startScan();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopScan();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startScan() {
        if (scanning) return;
        if (!hasPermission()) {
            broadcastStatus("Bluetooth-Berechtigung fehlt.");
            stopSelf();
            return;
        }
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            broadcastStatus("Bluetooth ist ausgeschaltet.");
            stopSelf();
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            broadcastStatus("BLE-Scanner konnte nicht gestartet werden.");
            stopSelf();
            return;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();
        try {
            scanner.startScan(null, settings, callback);
            scanning = true;
            broadcastStatus("Aktiver BLE-Scan läuft …");
        } catch (SecurityException e) {
            broadcastStatus("Bluetooth-Zugriff wurde von Android blockiert.");
            stopSelf();
        }
    }

    private void stopScan() {
        if (scanner != null && scanning && hasPermission()) {
            try { scanner.stopScan(callback); } catch (Exception ignored) { }
        }
        scanning = false;
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private final ScanCallback callback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            decode(result);
        }

        @Override
        public void onScanFailed(int errorCode) {
            broadcastStatus("BLE-Scanfehler: " + errorCode);
        }
    };

    private void decode(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record == null) return;
        String name = record.getDeviceName();
        if (name == null) {
            try { name = result.getDevice().getName(); } catch (SecurityException ignored) { }
        }
        if (name == null) name = "Unbekannt";

        SparseArray<byte[]> all = record.getManufacturerSpecificData();
        byte[] payload = all == null ? null : all.get(0x8801);
        boolean h5179Seen = name.toUpperCase(Locale.ROOT).contains("H5179");

        if (payload == null && h5179Seen && all != null) {
            for (int i = 0; i < all.size(); i++) {
                byte[] candidate = all.valueAt(i);
                if (candidate != null && candidate.length >= 9) {
                    payload = candidate;
                    break;
                }
            }
        }

        if (payload == null || payload.length < 9) {
            if (h5179Seen) broadcastStatus("H5179 erkannt – warte auf vollständiges Messpaket …");
            return;
        }

        short rawTemp = (short) ((payload[4] & 0xff) | ((payload[5] & 0xff) << 8));
        int rawHumidity = (payload[6] & 0xff) | ((payload[7] & 0xff) << 8);
        int battery = payload[8] & 0xff;
        float temperature = rawTemp / 100f;
        float humidity = rawHumidity / 100f;

        if (temperature < -40f || temperature > 100f || humidity < 0f || humidity > 100f || battery > 100) {
            if (h5179Seen) broadcastStatus("H5179-Paket erkannt, Werte aber unplausibel – Decoderprüfung nötig.");
            return;
        }

        saveReading(name, temperature, humidity, battery, result.getRssi());
    }

    private void saveReading(String name, float temperature, float humidity, int battery, int rssi) {
        long now = System.currentTimeMillis();
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit()
                .putString("sensorName", name)
                .putFloat("temperature", temperature)
                .putFloat("humidity", humidity)
                .putInt("battery", battery)
                .putInt("rssi", rssi)
                .putLong("timestamp", now);

        if (now - lastSavedAt >= 15000L) {
            String line = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.GERMANY).format(new Date(now))
                    + "   " + String.format(Locale.GERMANY, "%.2f °C   %.2f %%   %d %%   %d dBm", temperature, humidity, battery, rssi);
            String old = p.getString("history", "");
            String combined = line + (old.isEmpty() ? "" : "\n" + old);
            String[] rows = combined.split("\n");
            StringBuilder trimmed = new StringBuilder();
            for (int i = 0; i < rows.length && i < 30; i++) {
                if (i > 0) trimmed.append('\n');
                trimmed.append(rows[i]);
            }
            e.putString("history", trimmed.toString());
            lastSavedAt = now;
        }
        e.apply();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(String.format(Locale.GERMANY, "%.1f °C · %.1f %% · Akku %d %%", temperature, humidity, battery)));
        ClimateWidgetProvider.updateAll(this);
        broadcastStatus("H5179 live · Messwert empfangen");
    }

    private void broadcastStatus(String status) {
        Intent i = new Intent(ACTION_READING);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_STATUS, status);
        sendBroadcast(i);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "H5179 Sensor", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Zeigt an, dass der H5179-Bluetooth-Test läuft.");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setContentTitle("Grow H5179 Test")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
