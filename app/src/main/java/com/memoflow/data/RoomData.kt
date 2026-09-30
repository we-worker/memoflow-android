package com.memoflow.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "audio_chunks")
data class AudioChunkEntity(
    @PrimaryKey val id: String,
    val deviceId: String,
    val startTimeUtcMs: Long,
    val endTimeUtcMs: Long,
    val durationMs: Long,
    val audioPath: String,
    val codec: String,
    val container: String,
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Int,
    val fileSize: Long,
    val checksumSha256: String,
    val state: String,
    val schemaVersion: Int,
)

@Entity(tableName = "audio_ranges", primaryKeys = ["chunkId", "startOffsetMs", "endOffsetMs"])
data class AudioRangeEntity(
    val chunkId: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val type: String,
    val confidence: Float?,
    val modelId: String?,
    val modelVersion: String?,
)

@Entity(tableName = "transcript_segments")
data class TranscriptSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chunkId: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val text: String,
    val language: String?,
    val modelId: String?,
    val modelVersion: String?,
)

@Dao
interface ChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chunk: AudioChunkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRanges(ranges: List<AudioRangeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscripts(segments: List<TranscriptSegmentEntity>)

    @Query("SELECT * FROM audio_chunks ORDER BY startTimeUtcMs DESC")
    fun observe(): Flow<List<AudioChunkEntity>>

    @Query("SELECT * FROM audio_ranges WHERE chunkId = :chunkId ORDER BY startOffsetMs")
    fun observeRanges(chunkId: String): Flow<List<AudioRangeEntity>>

    @Query("SELECT * FROM transcript_segments WHERE chunkId = :chunkId ORDER BY startOffsetMs")
    fun observeTranscripts(chunkId: String): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT COUNT(*) FROM audio_ranges")
    fun observeRangeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM transcript_segments")
    fun observeTranscriptCount(): Flow<Int>

    @Query("SELECT * FROM audio_chunks WHERE state IN ('COMPLETE','UPLOAD_PENDING','FAILED') ORDER BY startTimeUtcMs")
    suspend fun pending(): List<AudioChunkEntity>

    @Query("SELECT * FROM audio_ranges WHERE chunkId = :chunkId ORDER BY startOffsetMs")
    suspend fun rangesForChunk(chunkId: String): List<AudioRangeEntity>

    @Query("UPDATE audio_chunks SET state = :state WHERE id = :id")
    suspend fun updateState(id: String, state: String)

    @Query("DELETE FROM transcript_segments WHERE chunkId = :chunkId")
    suspend fun deleteTranscripts(chunkId: String)

    @Query("DELETE FROM audio_ranges WHERE chunkId = :chunkId")
    suspend fun deleteRanges(chunkId: String)

    @Query("DELETE FROM audio_chunks WHERE id = :chunkId")
    suspend fun deleteChunk(chunkId: String)

    @Transaction
    suspend fun replaceTranscripts(chunkId: String, segments: List<TranscriptSegmentEntity>) {
        deleteTranscripts(chunkId)
        if (segments.isNotEmpty()) insertTranscripts(segments)
    }

    @Transaction
    suspend fun deleteBundle(chunkId: String) {
        deleteTranscripts(chunkId)
        deleteRanges(chunkId)
        deleteChunk(chunkId)
    }
}

@Database(
    entities = [AudioChunkEntity::class, AudioRangeEntity::class, TranscriptSegmentEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class MemoDatabase : RoomDatabase() {
    abstract fun chunks(): ChunkDao

    companion object {
        @Volatile private var INSTANCE: MemoDatabase? = null

        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """CREATE TABLE IF NOT EXISTS transcript_segments (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            chunkId TEXT NOT NULL,
                            startOffsetMs INTEGER NOT NULL,
                            endOffsetMs INTEGER NOT NULL,
                            text TEXT NOT NULL,
                            language TEXT,
                            modelId TEXT,
                            modelVersion TEXT
                        )""".trimIndent()
                    )
                }
            }

        fun get(context: Context): MemoDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoDatabase::class.java,
                    "memoflow.db",
                ).addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
