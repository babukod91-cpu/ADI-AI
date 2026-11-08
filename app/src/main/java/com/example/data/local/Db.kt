package com.example.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,                    // "user" | "adi"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val actionType: String? = null,
    val actionSummary: String? = null
)

@Dao
interface AdiDao {
    @Query("SELECT * FROM conversations ORDER BY timestamp ASC, id ASC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC, id DESC LIMIT :n")
    suspend fun recent(n: Int): List<ConversationEntity>

    @Insert
    suspend fun insert(e: ConversationEntity): Long

    @Query("DELETE FROM conversations")
    suspend fun clearAll()

    @Query("DELETE FROM conversations WHERE id NOT IN (SELECT id FROM conversations ORDER BY timestamp DESC, id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Database(entities = [ConversationEntity::class], version = 1, exportSchema = false)
abstract class AdiDatabase : RoomDatabase() {
    abstract fun dao(): AdiDao

    companion object {
        fun build(ctx: Context): AdiDatabase =
            Room.databaseBuilder(ctx.applicationContext, AdiDatabase::class.java, "adi.db")
                .fallbackToDestructiveMigration().build()
    }
}
