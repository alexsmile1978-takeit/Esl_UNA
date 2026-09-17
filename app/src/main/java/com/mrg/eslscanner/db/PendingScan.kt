package com.mrg.eslscanner.db

import androidx.room.*

@Entity(tableName = "pending_scans")
data class PendingScan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productCode: String,
    val eslBarcode: String,
    val storeCode: String,
    val scannedAt: Long = System.currentTimeMillis(),
    // PENDING -> INSERTED -> SENT ; or FAILED with lastError set
    val status: String = "PENDING",
    val lastError: String? = null
)

@Dao
interface PendingScanDao {

    @Insert
    suspend fun insert(scan: PendingScan): Long

    @Query("SELECT * FROM pending_scans WHERE status != 'SENT' ORDER BY scannedAt DESC")
    suspend fun getUnsent(): List<PendingScan>

    @Query("SELECT * FROM pending_scans ORDER BY scannedAt DESC LIMIT 200")
    suspend fun getRecent(): List<PendingScan>

    @Update
    suspend fun update(scan: PendingScan)

    @Query("DELETE FROM pending_scans WHERE status = 'SENT'")
    suspend fun clearSent()
}

@Database(entities = [PendingScan::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingScanDao(): PendingScanDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: android.content.Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "esl_scanner.db"
                ).build().also { INSTANCE = it }
            }
    }
}
