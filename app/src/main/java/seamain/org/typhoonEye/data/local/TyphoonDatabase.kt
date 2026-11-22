package seamain.org.typhoonEye.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2 adds [FavoriteTyphoonEntity]. origin/foss and origin/master were both
 * version 1, so the upgrade is [MIGRATION_1_2] on either line. The typhoon cache
 * table is left as-is; favorites are not part of that cache and are not dropped
 * when the live list is replaced.
 *
 * No destructive fallback: a missing migration must not wipe saved typhoons.
 */
@Database(
    entities = [TyphoonEntity::class, FavoriteTyphoonEntity::class],
    version = 2,
    exportSchema = false
)
abstract class TyphoonDatabase : RoomDatabase() {
    abstract fun typhoonDao(): TyphoonDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        private const val DB_NAME = "typhoon_eye.db"

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_FAVORITES)
            }
        }

        /**
         * Column order and nullability match [FavoriteTyphoonEntity] so Room's
         * post-migration validation accepts an upgraded database.
         */
        internal const val CREATE_FAVORITES =
            "CREATE TABLE IF NOT EXISTS `favorite_typhoons` (" +
                "`stableId` TEXT NOT NULL, " +
                "`sourceId` TEXT NOT NULL, " +
                "`trackId` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`englishName` TEXT NOT NULL, " +
                "`year` INTEGER, " +
                "`basin` TEXT NOT NULL, " +
                "`number` TEXT, " +
                "`status` TEXT NOT NULL, " +
                "`strong` TEXT NOT NULL, " +
                "`favoritedAtEpochMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`stableId`))"

        fun create(context: Context): TyphoonDatabase =
            Room.databaseBuilder(context.applicationContext, TyphoonDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
