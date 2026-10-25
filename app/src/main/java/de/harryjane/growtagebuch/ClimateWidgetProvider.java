package de.harryjane.growtagebuch;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ClimateWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) update(context, manager, id);
    }

    public static void updateAll(Context context) {
        AppWidgetManager m = AppWidgetManager.getInstance(context);
        ComponentName c = new ComponentName(context, ClimateWidgetProvider.class);
        for (int id : m.getAppWidgetIds(c)) update(context, m, id);
    }

    private static void update(Context c, AppWidgetManager m, int id) {
        SharedPreferences p = c.getSharedPreferences(H5179ScanService.PREFS, Context.MODE_PRIVATE);
        double t = Double.longBitsToDouble(p.getLong("temp_bits", Double.doubleToLongBits(Double.NaN)));
        double h = Double.longBitsToDouble(p.getLong("hum_bits", Double.doubleToLongBits(Double.NaN)));
        int batt = p.getInt("battery", -1);
        long ts = p.getLong("ts", 0);
        String env = p.getString("environment", "Grow-Tagebuch");
        int rssi = p.getInt("rssi", 0);

        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_climate);
        v.setTextViewText(R.id.widgetTitle, env == null || env.isEmpty() ? "Grow-Tagebuch" : env);
        v.setTextViewText(R.id.widgetTemp, Double.isNaN(t) ? "🌡 — °C" : String.format(Locale.GERMANY, "🌡 %.1f °C", t));
        v.setTextViewText(R.id.widgetHumidity, Double.isNaN(h) ? "💧 — %" : String.format(Locale.GERMANY, "💧 %.0f %%", h));
        String when = ts > 0 ? new SimpleDateFormat("HH:mm", Locale.GERMANY).format(new Date(ts)) : "noch keine Messung";
        String footer = "H5179 · " + when;
        if (batt >= 0) footer += " · 🔋 " + batt + " %";
        if (rssi != 0) footer += " · " + rssi + " dBm";
        v.setTextViewText(R.id.widgetFooter, footer);

        Intent open = new Intent(c, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.widgetRoot, pi);
        m.updateAppWidget(id, v);
    }
}
