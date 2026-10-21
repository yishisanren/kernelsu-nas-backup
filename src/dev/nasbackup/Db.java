package dev.nasbackup;
import android.database.sqlite.SQLiteDatabase;
import java.nio.file.Path;
final class Db {
    static SQLiteDatabase open(Path path,boolean write) {
        // Explicit sync mode also supports vendor app_process runtimes without an Application.
        return SQLiteDatabase.openDatabase(path.toFile(),new SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags((write?SQLiteDatabase.CREATE_IF_NECESSARY:SQLiteDatabase.OPEN_READONLY)|SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            .setSynchronousMode("FULL").build());
    }
}
