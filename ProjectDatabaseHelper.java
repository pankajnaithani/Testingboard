package com.whiteboard.cleanrecord;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class ProjectDatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "WhiteboardProjects.db";
    private static final int DATABASE_VERSION = 1;

    private static final String TABLE_PROJECTS = "projects";
    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";
    private static final String KEY_TIMESTAMP = "timestamp";
    private static final String KEY_DATA = "vector_data";

    public ProjectDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_TABLE = "CREATE TABLE " + TABLE_PROJECTS + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_NAME + " TEXT,"
                + KEY_TIMESTAMP + " INTEGER,"
                + KEY_DATA + " TEXT" + ")";
        db.execSQL(CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // FIXED TYPO HERE: Removed the stray character cleanly
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_PROJECTS);
        onCreate(db);
    }

    public long insertProject(String name, String jsonData) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KEY_NAME, name);
        values.put(KEY_TIMESTAMP, System.currentTimeMillis());
        values.put(KEY_DATA, jsonData);
        return db.insert(TABLE_PROJECTS, null, values);
    }

    public void updateProject(long id, String name, String jsonData) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(KEY_NAME, name);
        values.put(KEY_TIMESTAMP, System.currentTimeMillis());
        values.put(KEY_DATA, jsonData);
        db.update(TABLE_PROJECTS, values, KEY_ID + " = ?", new String[]{String.valueOf(id)});
    }

    public List<ProjectModel> getAllProjects() {
        List<ProjectModel> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_PROJECTS + " ORDER BY " + KEY_TIMESTAMP + " DESC", null);

        if (cursor.moveToFirst()) {
            do {
                ProjectModel project = new ProjectModel(
                        cursor.getLong(0),
                        cursor.getString(1),
                        cursor.getLong(2),
                        cursor.getString(3)
                );
                list.add(project);
            } while (cursor.moveToNext());
        }
        cursor.close();
        return list;
    }

    public void deleteProject(long id) {
        SQLiteDatabase db = this.getWritableDatabase();
        db.delete(TABLE_PROJECTS, KEY_ID + " = ?", new String[]{String.valueOf(id)});
    }
}
