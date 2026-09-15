package ai.hermes.mama.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Acceso a la lista de chats cacheada (B6). */
@Dao
interface ChatDao {
    /**
     * Lista tal cual la pinta la pantalla Chats: el orden del servidor
     * (`effective_last_active` DESC), preservado por el rank [ChatEntity.lastActive].
     */
    @Query("SELECT * FROM chats ORDER BY lastActive DESC, storedId ASC")
    fun observeChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats")
    suspend fun chats(): List<ChatEntity>

    /** Tope de [ChatEntity.lastActive]: la actividad local nueva se coloca encima (`+ 1`). */
    @Query("SELECT COALESCE(MAX(lastActive), 0.0) FROM chats")
    suspend fun maxLastActive(): Double

    @Query("SELECT * FROM chats WHERE storedId = :storedId LIMIT 1")
    suspend fun findByStoredId(storedId: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE runtimeId = :runtimeId LIMIT 1")
    suspend fun findByRuntimeId(runtimeId: String): ChatEntity?

    @Upsert
    suspend fun upsert(chat: ChatEntity)

    @Upsert
    suspend fun upsertAll(chats: List<ChatEntity>)

    /** Filas actualizadas (0 si el storedId no está cacheado). */
    @Query("UPDATE chats SET title = :title WHERE storedId = :storedId")
    suspend fun setTitleByStoredId(
        storedId: String,
        title: String,
    ): Int

    /** Filas actualizadas (0 si el runtimeId no está cacheado). */
    @Query("UPDATE chats SET title = :title WHERE runtimeId = :runtimeId")
    suspend fun setTitleByRuntimeId(
        runtimeId: String,
        title: String,
    ): Int

    /** Borra el chat; sus mensajes caen por la FK CASCADE. */
    @Query("DELETE FROM chats WHERE storedId = :storedId")
    suspend fun deleteByStoredId(storedId: String): Int
}

/** Acceso al transcript cacheado de un chat (B6). */
@Dao
interface MessageDao {
    /** Transcript en orden de inserción (= orden del transcript; ver [MessageEntity.rowId]). */
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY rowId ASC")
    fun observeMessages(chatId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteForChat(chatId: String)
}
