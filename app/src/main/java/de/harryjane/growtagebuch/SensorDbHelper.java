package de.harryjane.growtagebuch;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SensorDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "grow_sensors.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "sensor_readings";

    public SensorDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ts INTEGER NOT NULL," +
                "temperature REAL NOT NULL," +
                "humidity REAL NOT NULL," +
                "battery INTEGER," +
                "rssi INTEGER," +
                "name TEXT," +
                "address TEXT," +
                "environment TEXT" +
                ")");
        db.execSQL("CREATE INDEX idx_sensor_ts ON " + TABLE + "(ts)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public long insert(long ts, double temp, double humidity, int battery, int rssi,
                       String name, String address, String environment) {
        ContentValues v = new ContentValues();
        v.put("ts", ts);
        v.put("temperature", temp);
        v.put("humidity", humidity);
        if (battery >= 0) v.put("battery", battery);
        v.put("rssi", rssi);
        v.put("name", name == null ? "Govee H5179" : name);
        v.put("address", address == null ? "" : address);
        v.put("environment", environment == null ? "" : environment);
        return getWritableDatabase().insert(TABLE, null, v);
    }

    public String queryJson(long since, int limit) {
        limit = Math.max(100, Math.min(limit, 50000));
        Cursor c = getReadableDatabase().query(TABLE,
                new String[]{"ts","temperature","humidity","battery","rssi","name","address","environment"},
                since > 0 ? "ts>=?" : null,
                since > 0 ? new String[]{String.valueOf(since)} : null,
                null, null, "ts DESC", String.valueOf(limit));
        List<JSONObject> rows = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("ts", c.getLong(0));
                o.put("temperature", c.getDouble(1));
                o.put("humidity", c.getDouble(2));
                o.put("battery", c.isNull(3) ? -1 : c.getInt(3));
                o.put("rssi", c.getInt(4));
                o.put("name", c.getString(5));
                o.put("address", c.getString(6));
                o.put("environment", c.getString(7));
                rows.add(o);
            }
        } catch (Exception ignored) {
        } finally {
            c.close();
        }
        Collections.reverse(rows);
        JSONArray a = new JSONArray();
        for (JSONObject o : rows) a.put(o);
        return a.toString();
    }

    public int count() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; }
        finally { c.close(); }
    }

    public void clear() {
        getWritableDatabase().delete(TABLE, null, null);
    }
}
