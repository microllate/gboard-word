package com.microllate.gboardword;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

final class PersonalDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "personal_words.db";
    private static final int DB_VERSION = 1;

    PersonalDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE words (" +
                "pinyin TEXT NOT NULL," +
                "phrase TEXT NOT NULL," +
                "count INTEGER NOT NULL DEFAULT 1," +
                "last_used INTEGER NOT NULL," +
                "PRIMARY KEY(pinyin, phrase))");
        db.execSQL("CREATE INDEX idx_words_pinyin ON words(pinyin)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    synchronized int record(String pinyin, String phrase) {
        if (pinyin == null || phrase == null || pinyin.isEmpty() || phrase.isEmpty()) return 0;
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL(
                "INSERT INTO words(pinyin, phrase, count, last_used) VALUES(?,?,1,?) " +
                "ON CONFLICT(pinyin, phrase) DO UPDATE SET " +
                "count=count+1,last_used=excluded.last_used",
                new Object[]{pinyin, phrase, System.currentTimeMillis()});

        try (Cursor c = db.rawQuery(
                "SELECT count FROM words WHERE pinyin=? AND phrase=?",
                new String[]{pinyin, phrase})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
}
