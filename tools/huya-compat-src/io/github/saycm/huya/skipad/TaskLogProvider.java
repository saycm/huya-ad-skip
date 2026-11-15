package io.github.saycm.huya.skipad;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import java.util.ArrayList;
import java.util.Arrays;

public final class TaskLogProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://io.github.saycm.huya.skipad.tasks");
    private SQLiteOpenHelper helper;

    @Override
    public boolean onCreate() {
        helper = new SQLiteOpenHelper(getContext(), "daily_tasks.db", null, 2) {
            @Override public void onCreate(SQLiteDatabase db) {
                db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "time INTEGER NOT NULL, task TEXT NOT NULL, target TEXT NOT NULL, "
                        + "state TEXT NOT NULL, detail TEXT NOT NULL, origin TEXT)");
                db.execSQL("CREATE UNIQUE INDEX events_origin ON events(origin)");
            }
            @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
                if (oldVersion < 2) {
                    db.execSQL("ALTER TABLE events ADD COLUMN origin TEXT");
                    db.execSQL("CREATE UNIQUE INDEX events_origin ON events(origin)");
                }
            }
        };
        return true;
    }

    @Override
    public synchronized Bundle call(String method, String arg, Bundle extras) {
        boolean owner = Binder.getCallingUid() == Process.myUid();
        String[] callers = getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
        boolean huya = callers != null && Arrays.asList(callers).contains("com.duowan.kiwi");
        if (!owner && !huya) throw new SecurityException("Caller is not allowed");
        SQLiteDatabase db = helper.getWritableDatabase();
        Bundle result = new Bundle();
        if ("import".equals(method)) {
            ArrayList<Bundle> incoming = extras == null ? null : extras.getParcelableArrayList("events");
            if (incoming == null || incoming.size() > 50) throw new IllegalArgumentException("Invalid batch");
            long cutoff = getContext().getSharedPreferences("task_log_display", 0).getLong("cutoff", 0);
            db.beginTransaction();
            try {
                for (Bundle entry : incoming) {
                    String key = entry.getString("key", "");
                    if (key.isEmpty() || key.length() > 64 || entry.getLong("time") <= cutoff) continue;
                    ContentValues row = new ContentValues();
                    row.put("origin", key);
                    row.put("time", entry.getLong("time"));
                    for (String field : new String[]{"task", "target", "state", "detail"}) {
                        String value = entry.getString(field, "");
                        row.put(field, value.substring(0, Math.min(value.length(), 400)));
                    }
                    db.insertWithOnConflict("events", null, row, SQLiteDatabase.CONFLICT_IGNORE);
                }
                db.execSQL("DELETE FROM events WHERE id NOT IN "
                        + "(SELECT id FROM events ORDER BY time DESC, id DESC LIMIT 500)");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            getContext().getSharedPreferences("task_log_display", 0).edit()
                    .putLong("last_sync", System.currentTimeMillis()).apply();
        } else if ("read".equals(method)) {
            if (!owner) throw new SecurityException("Only module can read logs");
            ArrayList<Bundle> entries = new ArrayList<>();
            try (Cursor rows = db.rawQuery("SELECT time, task, target, state, detail "
                    + "FROM events ORDER BY time DESC, id DESC LIMIT 300", null)) {
                while (rows.moveToNext()) {
                    Bundle entry = new Bundle();
                    entry.putLong("time", rows.getLong(0));
                    entry.putString("task", rows.getString(1));
                    entry.putString("target", rows.getString(2));
                    entry.putString("state", rows.getString(3));
                    entry.putString("detail", rows.getString(4));
                    entries.add(entry);
                }
            }
            result.putParcelableArrayList("events", entries);
            result.putLong("last_sync", getContext().getSharedPreferences("task_log_display", 0)
                    .getLong("last_sync", 0));
        } else if ("clear".equals(method)) {
            if (!owner) throw new SecurityException("Only module can clear logs");
            getContext().getSharedPreferences("task_log_display", 0).edit()
                    .putLong("cutoff", System.currentTimeMillis()).commit();
            db.delete("events", null, null);
        } else {
            throw new IllegalArgumentException("Unknown action");
        }
        return result;
    }

    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) {
        throw new UnsupportedOperationException();
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
