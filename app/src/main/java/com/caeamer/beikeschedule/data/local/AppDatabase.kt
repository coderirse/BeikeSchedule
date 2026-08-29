package com.caeamer.beikeschedule.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CourseEntity::class, SectionTimeEntity::class, GradeEntity::class, ExamEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun courseDao(): CourseDao
    abstract fun sectionTimeDao(): SectionTimeDao
    abstract fun gradeDao(): GradeDao
    abstract fun examDao(): ExamDao

    companion object {
        /** v1 → v2：新增 grade 表（课程/节次数据原样保留）。 */
        private val MIGRATE_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `grade` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kcdm` TEXT NOT NULL, `kcmc` TEXT NOT NULL, " +
                        "`xnxq` TEXT NOT NULL, `xnxqmc` TEXT NOT NULL, " +
                        "`kcxz` TEXT NOT NULL, `kclb` TEXT NOT NULL, " +
                        "`xf` REAL NOT NULL, `zzcj` TEXT NOT NULL, " +
                        "`bkcx` TEXT NOT NULL, `yxmc` TEXT NOT NULL, `sffx` INTEGER NOT NULL)",
                )
            }
        }

        /** v2 → v3：course 表新增 hidden 列（教务课程隐藏而非删除）。 */
        private val MIGRATE_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `course` ADD COLUMN `hidden` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3 → v4：grade 加排名/考核方式列 + 新增 exam 表。 */
        private val MIGRATE_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `grade` ADD COLUMN `pm` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `grade` ADD COLUMN `zrs` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `grade` ADD COLUMN `khfs` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `exam` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kcdm` TEXT NOT NULL, `kcmc` TEXT NOT NULL, " +
                        "`kslx` TEXT NOT NULL, `kssjms` TEXT NOT NULL, " +
                        "`ksrq` TEXT NOT NULL, `kssj` TEXT NOT NULL, `jssj` TEXT NOT NULL, " +
                        "`cdmc` TEXT NOT NULL, `zwh` TEXT NOT NULL, `jkjsbz` TEXT NOT NULL, " +
                        "`kkyxmc` TEXT NOT NULL, `xnxq` TEXT NOT NULL)",
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "beike_schedule.db",
                ).addMigrations(MIGRATE_1_2, MIGRATE_2_3, MIGRATE_3_4).build().also { instance = it }
            }
    }
}
