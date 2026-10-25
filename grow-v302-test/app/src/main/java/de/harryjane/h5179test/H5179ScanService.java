package de.harryjane.h5179test;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.util.SparseArray;
import java.util.*;

public class H5179ScanService extends Service {
 public static final String PREFS="h5179_readings", ACTION_READING="de.harryjane.h5179test.READING", EXTRA_STATUS="status";
 private static final String CH="h5179_scan"; private static final int NID=5179;
 private BluetoothLeScanner scanner; private boolean scanning; private long lastDiag; private int packets;
 private final LinkedHashMap<String,DeviceInfo> seen=new LinkedHashMap<>();
 @Override public void onCreate(){super.onCreate();channel();foreground(note("BLE-Rohdiagnose läuft …"));}
 @Override public int onStartCommand(Intent i,int f,int id){startScan();return START_STICKY;}
 @Override public void onDestroy(){stopScan();super.onDestroy();}
 @Override public IBinder onBind(Intent i){return null;}
 private void foreground(Notification n){if(Build.VERSION.SDK_INT>=29)startForeground(NID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(NID,n);}
 private boolean perm(){if(Build.VERSION.SDK_INT>=31)return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
 private void startScan(){if(scanning)return;if(!perm()){send("Bluetooth-Berechtigung fehlt.");return;}BluetoothManager bm=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);BluetoothAdapter a=bm==null?null:bm.getAdapter();if(a==null||!a.isEnabled()){send("Bluetooth ist ausgeschaltet.");return;}scanner=a.getBluetoothLeScanner();ScanSettings s=new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).build();scanner.startScan(null,s,cb);scanning=true;send("BLE-Rohdiagnose v0.3 läuft. Alle Geräte werden protokolliert …");}
 private void stopScan(){if(scanner!=null&&scanning&&perm())try{scanner.stopScan(cb);}catch(Exception ignored){}scanning=false;}
 private final ScanCallback cb=new ScanCallback(){@Override public void onScanResult(int t,ScanResult r){scan(r);}@Override public void onBatchScanResults(List<ScanResult> rs){for(ScanResult r:rs)scan(r);}@Override public void onScanFailed(int e){send("BLE-Scanfehler: "+e);}};
 private void scan(ScanResult r){packets++;ScanRecord rec=r.getScanRecord();if(rec==null)return;String addr="?";try{addr=r.getDevice().getAddress();}catch(Exception ignored){}String name=rec.getDeviceName();if(name==null)try{name=r.getDevice().getName();}catch(Exception ignored){}if(name==null)name="Unbekannt";SparseArray<byte[]> m=rec.getManufacturerSpecificData();String mf=mfg(m);String raw=rawMfg(m);DeviceInfo d=new DeviceInfo(addr,name,r.getRssi(),mf,raw,System.currentTimeMillis());seen.put(addr,d);while(seen.size()>40)seen.remove(seen.keySet().iterator().next());saveDiagnostics();tryDecode(name,r,m);long now=System.currentTimeMillis();if(now-lastDiag>1200){lastDiag=now;send("BLE v0.3: "+seen.size()+" Geräte · "+packets+" Pakete\nLetztes: "+name+" · "+addr+" · "+r.getRssi()+" dBm · MFG "+mf);}}
 private void tryDecode(String name,ScanResult r,SparseArray<byte[]> m){if(m==null)return;for(int i=0;i<m.size();i++){int id=m.keyAt(i);byte[] p=m.valueAt(i);if(p==null)continue;Reading x=decode9(p);if(x!=null&&(id==0x8801||name.toUpperCase(Locale.ROOT).contains("5179")||name.toUpperCase(Locale.ROOT).contains("GOVEE"))){saveReading(name,x.t,x.h,x.b,r.getRssi());return;}}}
 private Reading decode9(byte[]p){if(p.length<9)return null;short rt=(short)((p[4]&255)|((p[5]&255)<<8));int rh=(p[6]&255)|((p[7]&255)<<8),b=p[8]&255;float t=rt/100f,h=rh/100f;return t>=-40&&t<=80&&h>=0&&h<=100&&b<=100?new Reading(t,h,b):null;}
 private void saveDiagnostics(){StringBuilder b=new StringBuilder();ArrayList<DeviceInfo> list=new ArrayList<>(seen.values());Collections.sort(list,(x,y)->Integer.compare(y.rssi,x.rssi));int n=0;for(DeviceInfo d:list){if(n++>=25)break;b.append(d.name).append(" | ").append(d.address).append(" | ").append(d.rssi).append(" dBm | MFG ").append(d.mfg);if(!d.raw.isEmpty())b.append(" | ").append(d.raw);b.append('\n');}getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("diagnostics",b.toString()).putInt("diagPackets",packets).apply();}
 private String mfg(SparseArray<byte[]>a){if(a==null||a.size()==0)return"—";StringBuilder s=new StringBuilder();for(int i=0;i<a.size();i++){if(i>0)s.append(',');byte[]b=a.valueAt(i);s.append(String.format(Locale.ROOT,"0x%04X/%dB",a.keyAt(i),b==null?0:b.length));}return s.toString();}
 private String rawMfg(SparseArray<byte[]>a){if(a==null)return"";StringBuilder s=new StringBuilder();for(int i=0;i<a.size();i++){byte[]b=a.valueAt(i);if(b==null)continue;if(s.length()>0)s.append(' ');s.append(String.format(Locale.ROOT,"%04X:",a.keyAt(i)));for(int j=0;j<b.length&&j<32;j++)s.append(String.format(Locale.ROOT,"%02X",b[j]&255));}return s.toString();}
 private void saveReading(String name,float t,float h,int bat,int rssi){long now=System.currentTimeMillis();getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("sensorName",name).putFloat("temperature",t).putFloat("humidity",h).putInt("battery",bat).putInt("rssi",rssi).putLong("timestamp",now).apply();((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NID,note(String.format(Locale.GERMANY,"%.1f °C · %.1f %%",t,h)));ClimateWidgetProvider.updateAll(this);send("H5179 LIVE · "+name+" · "+String.format(Locale.GERMANY,"%.2f °C · %.2f %%",t,h));}
 private void send(String s){Intent i=new Intent(ACTION_READING);i.setPackage(getPackageName());i.putExtra(EXTRA_STATUS,s);sendBroadcast(i);}
 private void channel(){if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CH,"H5179 Sensor",NotificationManager.IMPORTANCE_LOW));}
 private Notification note(String text){PendingIntent p=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CH):new Notification.Builder(this);return b.setContentTitle("Grow H5179 Test v0.3").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentIntent(p).setOngoing(true).build();}
 private static class Reading{float t,h;int b;Reading(float t,float h,int b){this.t=t;this.h=h;this.b=b;}}
 private static class DeviceInfo{String address,name,mfg,raw;int rssi;long ts;DeviceInfo(String a,String n,int r,String m,String raw,long ts){address=a;name=n;rssi=r;mfg=m;this.raw=raw;this.ts=ts;}}
}
