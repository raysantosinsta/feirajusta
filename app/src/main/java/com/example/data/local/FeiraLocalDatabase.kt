package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

enum class SyncStatus {
    PENDING,
    SYNCING,
    FAILED
}

@Entity(tableName = "pending_prices")
data class PendingPriceEntity(
    @PrimaryKey val localId: String,
    val userId: String,
    val productName: String,
    val normalizedName: String,
    val brand: String,
    val category: String,
    val unitLabel: String,
    val priceCents: Long,
    val marketId: String,
    val marketName: String,
    val isPromo: Boolean,
    val recordedAtMillis: Long,
    val localPhotoPath: String? = null,
    val syncStatus: String = SyncStatus.PENDING.name,
    val lastError: String? = null
)

@Dao
interface PendingPriceDao {
    @Query("SELECT * FROM pending_prices WHERE userId = :userId ORDER BY recordedAtMillis DESC")
    fun observePendingForUser(userId: String): Flow<List<PendingPriceEntity>>

    @Query("SELECT * FROM pending_prices WHERE userId = :userId ORDER BY recordedAtMillis ASC")
    suspend fun getPendingForUser(userId: String): List<PendingPriceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingPriceEntity)

    @Query("UPDATE pending_prices SET syncStatus = :status, lastError = :error WHERE localId = :localId")
    suspend fun updateSyncStatus(localId: String, status: String, error: String?)

    @Query("DELETE FROM pending_prices WHERE localId = :localId")
    suspend fun deleteById(localId: String)
}

@Database(entities = [PendingPriceEntity::class], version = 2, exportSchema = false)
abstract class FeiraLocalDatabase : RoomDatabase() {
    abstract fun pendingPriceDao(): PendingPriceDao

    companion object {
        @Volatile
        private var INSTANCE: FeiraLocalDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_prices ADD COLUMN localPhotoPath TEXT")
            }
        }

        fun getInstance(context: Context): FeiraLocalDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FeiraLocalDatabase::class.java,
                    "feirajusta_offline.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
