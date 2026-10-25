package de.harryjane.h5179test;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public class ClimateWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) updateOne(context, manager, id);
    }

    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, ClimateWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        for (int id : ids) updateOne(context, manager, id);
    }

    private static void updateOne(Context context, AppWidgetManager manager, int id) {
        SharedPreferences p = context.getSharedPreferences(H5179ScanService.PREFS, Context.MODE_PRIVATE);
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_climate);
        boolean has = p.contains("temperature");
        String temp = has ? String.format(Locale.GERMANY, "%.1f °C", p.getFloat("temperature", 0f)) : "— °C";
        String hum = has ? String.format(Locale.GERMANY, "%.1f %%", p.getFloat("humidity", 0f)) : "— %";
        int battery = p.getInt("battery", -1);
        int rssi = p.getInt("rssi", 0);
        long ts = p.getLong("timestamp", 0L);
        String meta = has ? "Akku " + battery + " % · " + rssi + " dBm" : "Noch keine Sensordaten";
        String time = ts > 0 ? "Stand " + DateFormat.getTimeInstance(DateFormat.SHORT, Locale.GERMANY).format(new Date(ts)) : "H5179 Test";

        v.setTextViewText(R.id.widgetTemp, temp);
        v.setTextViewText(R.id.widgetHumidity, hum);
        v.setTextViewText(R.id.widgetMeta, meta);
        v.setTextViewText(R.id.widgetTime, time);

        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.widgetRoot, pi);
        manager.updateAppWidget(id, v);
    }
}
