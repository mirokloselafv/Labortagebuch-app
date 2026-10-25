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
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.SparseArray;

import java.text.DateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class H5179ScanService extends Service {
    public static final String PREFS = "h5179_readings";
    public static final String ACTION_READING = "de.harryjane.h5179test.READING";
    public static final String EXTRA_STATUS = "status";
    private static final String CHANNEL_ID = "h5179_scan";
    private static final int NOTIFICATION_ID = 5179;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private long lastSavedAt;
    private long lastDiagnosticAt;
    private int packets;
    private int goveePackets;
    private final Set<String> devices = new HashSet<>();

    @Override public void onCreate() { super.onCreate(); createNotificationChannel(); startForegroundCompat(buildNotification("Warte auf H5179 …")); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { startScan(); return START_STICKY; }
    @Override public void onDestroy() { stopScan(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION_ID, n);
    }

    private void startScan() {
        if (scanning) return;
        if (!hasPermission()) { broadcastStatus("Bluetooth-Berechtigung fehlt."); stopSelf(); return; }
        BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null||!adapter.isEnabled()){broadcastStatus("Bluetooth ist ausgeschaltet.");stopSelf();return;}
        scanner=adapter.getBluetoothLeScanner();
        if(scanner==null){broadcastStatus("BLE-Scanner konnte nicht gestartet werden.");stopSelf();return;}
        ScanSettings settings=new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).build();
        try { scanner.startScan(null,settings,callback); scanning=true; broadcastStatus("BLE-Diagnose läuft … warte auf H5179/GV5179."); }
        catch(SecurityException e){broadcastStatus("Bluetooth-Zugriff wurde von Android blockiert.");stopSelf();}
    }

    private void stopScan(){ if(scanner!=null&&scanning&&hasPermission())try{scanner.stopScan(callback);}catch(Exception ignored){} scanning=false; }
    private boolean hasPermission(){ if(Build.VERSION.SDK_INT>=31)return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED; return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED; }

    private final ScanCallback callback=new ScanCallback(){
        @Override public void onScanResult(int callbackType, ScanResult result){decode(result);}
        @Override public void onBatchScanResults(java.util.List<ScanResult> results){for(ScanResult r:results)decode(r);}
        @Override public void onScanFailed(int errorCode){broadcastStatus("BLE-Scanfehler: "+errorCode);}
    };

    private void decode(ScanResult result){
        packets++;
        ScanRecord record=result.getScanRecord(); if(record==null)return;
        String address="?"; try{address=result.getDevice().getAddress();devices.add(address);}catch(SecurityException ignored){}
        String name=record.getDeviceName(); if(name==null)try{name=result.getDevice().getName();}catch(SecurityException ignored){}
        if(name==null)name="Unbekannt";
        String upper=name.toUpperCase(Locale.ROOT);
        boolean nameMatch=upper.contains("H5179")||upper.contains("GV5179")||upper.contains("GOVEE");
        SparseArray<byte[]> manufacturers=record.getManufacturerSpecificData();
        byte[] best=null; int manufacturerId=-1;
        if(manufacturers!=null){
            for(int i=0;i<manufacturers.size();i++){
                int id=manufacturers.keyAt(i); byte[] b=manufacturers.valueAt(i);
                if(id==0x8801){best=b;manufacturerId=id;break;}
                if(best==null&&b!=null&&b.length>=5&&(nameMatch||id==0xEC88||id==0x0188)){best=b;manufacturerId=id;}
            }
        }
        if(nameMatch||manufacturerId==0x8801)goveePackets++;
        if(best!=null){
            Reading r=decodeKnown(best);
            if(r==null)r=decodePacked(best);
            if(r!=null){saveReading(name,r.temperature,r.humidity,r.battery,result.getRssi());return;}
        }
        // Some firmware exposes useful bytes as service data rather than manufacturer data.
        Map<ParcelUuid,byte[]> services=record.getServiceData();
        if(services!=null)for(Map.Entry<ParcelUuid,byte[]> entry:services.entrySet()){
            byte[] b=entry.getValue(); if(b==null)continue;
            Reading r=decodeKnown(b); if(r==null)r=decodePacked(b);
            if(r!=null&&(nameMatch||upper.contains("GVH"))){saveReading(name,r.temperature,r.humidity,r.battery,result.getRssi());return;}
        }
        long now=System.currentTimeMillis();
        if(now-lastDiagnosticAt>1500){
            lastDiagnosticAt=now;
            String ids=manufacturerSummary(manufacturers);
            broadcastStatus("BLE Diagnose: "+devices.size()+" Geräte · "+packets+" Pakete · Govee/H5179 "+goveePackets+" · zuletzt "+name+" · "+result.getRssi()+" dBm · MFG "+ids);
        }
    }

    private Reading decodeKnown(byte[] p){
        // H5179/GV5179 common 9-byte payload: temp int16 LE @4, RH uint16 LE @6, battery @8.
        if(p.length>=9){
            short rt=(short)((p[4]&255)|((p[5]&255)<<8)); int rh=(p[6]&255)|((p[7]&255)<<8); int bat=p[8]&255;
            float t=rt/100f,h=rh/100f; if(valid(t,h,bat))return new Reading(t,h,bat);
        }
        return null;
    }

    private Reading decodePacked(byte[] p){
        // Older/common Govee packed format: 24-bit value = temp*1000 + humidity*10, battery follows.
        for(int off=0;off+3<p.length;off++){
            int raw=(p[off]&255)|((p[off+1]&255)<<8)|((p[off+2]&255)<<16);
            int signed=raw; boolean neg=(raw&0x800000)!=0; if(neg)signed=-(raw&0x7fffff);
            float t=(signed/1000)/10f; float h=(Math.abs(signed)%1000)/10f; int bat=p[off+3]&255;
            if(valid(t,h,bat))return new Reading(t,h,bat);
        }
        return null;
    }

    private boolean valid(float t,float h,int b){return t>=-40&&t<=80&&h>=0&&h<=100&&b>=0&&b<=100;}
    private String manufacturerSummary(SparseArray<byte[]> a){if(a==null||a.size()==0)return "—";StringBuilder s=new StringBuilder();for(int i=0;i<a.size();i++){if(i>0)s.append(',');byte[]b=a.valueAt(i);s.append(String.format(Locale.ROOT,"0x%04X/%dB",a.keyAt(i),b==null?0:b.length));}return s.toString();}
    private static class Reading{final float temperature,humidity;final int battery;Reading(float t,float h,int b){temperature=t;humidity=h;battery=b;}}

    private void saveReading(String name,float temperature,float humidity,int battery,int rssi){
        long now=System.currentTimeMillis(); SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);
        SharedPreferences.Editor e=p.edit().putString("sensorName",name).putFloat("temperature",temperature).putFloat("humidity",humidity).putInt("battery",battery).putInt("rssi",rssi).putLong("timestamp",now);
        if(now-lastSavedAt>=15000L){String line=DateFormat.getTimeInstance(DateFormat.MEDIUM,Locale.GERMANY).format(new Date(now))+"   "+String.format(Locale.GERMANY,"%.2f °C   %.2f %%   %d %%   %d dBm",temperature,humidity,battery,rssi);String old=p.getString("history","");String combined=line+(old.isEmpty()?"":"\n"+old);String[]rows=combined.split("\n");StringBuilder trimmed=new StringBuilder();for(int i=0;i<rows.length&&i<30;i++){if(i>0)trimmed.append('\n');trimmed.append(rows[i]);}e.putString("history",trimmed.toString());lastSavedAt=now;} e.apply();
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.notify(NOTIFICATION_ID,buildNotification(String.format(Locale.GERMANY,"%.1f °C · %.1f %% · Akku %d %%",temperature,humidity,battery)));ClimateWidgetProvider.updateAll(this);broadcastStatus("H5179 LIVE · "+name+" · "+String.format(Locale.GERMANY,"%.2f °C · %.2f %% · %d dBm",temperature,humidity,rssi));
    }
    private void broadcastStatus(String status){Intent i=new Intent(ACTION_READING);i.setPackage(getPackageName());i.putExtra(EXTRA_STATUS,status);sendBroadcast(i);}
    private void createNotificationChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL_ID,"H5179 Sensor",NotificationManager.IMPORTANCE_LOW);c.setDescription("H5179 Bluetooth-Test und Diagnose");((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);}}
    private Notification buildNotification(String text){Intent open=new Intent(this,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this);return b.setContentTitle("Grow H5179 Test v0.2").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentIntent(pi).setOngoing(true).build();}
}
