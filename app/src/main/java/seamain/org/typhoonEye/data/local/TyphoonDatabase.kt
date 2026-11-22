package seamain.org.typhoonEye.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2 adds [FavoriteTyphoonEntity]. foss and master were both version 1
 * before this feature; both move to version 2 with migration 1 → 2.
 * Existing typhoon rows are kept. There is no destructive fallback.
 */
@Database(
    entities = [TyphoonEntity::class, FavoriteTyphoonEntity::class],
    version = 2,
    exportSchema = false
)
abstract class TyphoonDatabase : RoomDatabase() {
    abstract fun typhoonDao(): TyphoonDao

    abstract fun favoriteTyphoonDao(): FavoriteTyphoonDao

    companion object {
        private const val DB_NAME = "typhoon_eye.db"

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorite_typhoons` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`englishName` TEXT NOT NULL, " +
                        "`year` INTEGER NOT NULL, " +
                        "`basin` TEXT NOT NULL, " +
                        "`favoritedAtEpochMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        fun create(context: Context): TyphoonDatabase =
            Room.databaseBuilder(context.applicationContext, TyphoonDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
