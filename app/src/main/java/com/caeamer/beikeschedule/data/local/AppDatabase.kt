package com.caeamer.beikeschedule.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CourseEntity::class, SectionTimeEntity::class, GradeEntity::class, ExamEntity::class, TodoEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun courseDao(): CourseDao
    abstract fun sectionTimeDao(): SectionTimeDao
    abstract fun gradeDao(): GradeDao
    abstract fun examDao(): ExamDao
    abstract fun todoDao(): TodoDao

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

        /** v4 → v5：新增 todo 表（个人日程，原有课程/成绩/考试数据原样保留）。 */
        private val MIGRATE_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `todo` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `note` TEXT NOT NULL DEFAULT '', " +
                        "`repeatMode` INTEGER NOT NULL DEFAULT 0, " +
                        "`weekdays` TEXT NOT NULL DEFAULT '0111110', " +
                        "`date` TEXT NOT NULL DEFAULT '', `time` TEXT NOT NULL, " +
                        "`remindMinutes` INTEGER NOT NULL DEFAULT 15, " +
                        "`colorIndex` INTEGER NOT NULL DEFAULT 0, " +
                        "`lastDoneDate` TEXT NOT NULL DEFAULT '')",
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
                )
                    .addMigrations(MIGRATE_1_2, MIGRATE_2_3, MIGRATE_3_4, MIGRATE_4_5)
                    // 迁移失败的兜底保险丝。
                    //
                    // **只兜"找不到迁移路径"这一种情况**（Room 的 fallbackToDestructiveMigration
                    // 语义：findMigrationPath 返回 null 且 isMigrationRequired 为真时才
                    // dropAllTables 重建）。迁移 SQL 自身抛异常、或迁移后 schema 校验失败
                    // （"Migration didn't properly handle: ..."，例如字段类型不符、
                    // 历史版本写坏过表结构）都**不会**走这条兜底，仍是
                    // IllegalStateException → **启动即崩且无法自愈**，用户只能清应用数据。
                    // 当前四条迁移与 3/4/5.json 逐列核对一致、迁移链完整，
                    // 所以这里目前是"备用保险丝"，不要把它当成万能兜底。
                    //
                    // 注意也不兜「用户数据丢失」：迁移正常时数据完整保留，此声明不会被触发。
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
