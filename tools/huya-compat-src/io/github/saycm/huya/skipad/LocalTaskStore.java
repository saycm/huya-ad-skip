package io.github.saycm.huya.skipad;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.UUID;

final class LocalTaskStore extends SQLiteOpenHelper {
    LocalTaskStore(Context context) { super(context, "fuckad_daily_tasks.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE runs (run_key TEXT PRIMARY KEY, started INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, event_key TEXT UNIQUE NOT NULL, "
                + "time INTEGER NOT NULL, task TEXT NOT NULL, target TEXT NOT NULL, state TEXT NOT NULL, detail TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    synchronized boolean claim(String key) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues row = new ContentValues();
        row.put("run_key", key);
        row.put("started", System.currentTimeMillis());
        boolean claimed = db.insertWithOnConflict("runs", null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1;
        db.delete("runs", "started < ?", new String[]{Long.toString(System.currentTimeMillis() - 35L * 86400000L)});
        return claimed;
    }

    synchronized void append(String task, String target, String state, String detail) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues row = new ContentValues();
        row.put("event_key", UUID.randomUUID().toString());
        row.put("time", System.currentTimeMillis());
        row.put("task", trim(task)); row.put("target", trim(target));
        row.put("state", trim(state)); row.put("detail", trim(detail));
        db.insertOrThrow("events", null, row);
        db.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 500)");
    }

    synchronized ArrayList<Bundle> events() {
        ArrayList<Bundle> entries = new ArrayList<>();
        try (Cursor rows = getReadableDatabase().rawQuery(
                "SELECT event_key,time,task,target,state,detail FROM events ORDER BY id", null)) {
            while (rows.moveToNext()) {
                Bundle entry = new Bundle();
                entry.putString("key", rows.getString(0));
                entry.putLong("time", rows.getLong(1));
                entry.putString("task", rows.getString(2));
                entry.putString("target", rows.getString(3));
                entry.putString("state", rows.getString(4));
                entry.putString("detail", rows.getString(5));
                entries.add(entry);
            }
        }
        return entries;
    }

    private String trim(String value) {
        return value.substring(0, Math.min(value.length(), 400));
    }
}
