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
    /** Lista tal cual la pinta la pantalla Chats: más reciente primero (§2.3 devuelve ese orden). */
    @Query("SELECT * FROM chats ORDER BY startedAt DESC, storedId ASC")
    fun observeChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats")
    suspend fun chats(): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE storedId = :storedId LIMIT 1")
    suspend fun findByStoredId(storedId: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE runtimeId = :runtimeId LIMIT 1")
    suspend fun findByRuntimeId(runtimeId: String): ChatEntity?

    @Upsert
    suspend fun upsert(chat: ChatEntity)

    @Upsert
    suspend fun upsertAll(chats: List<ChatEntity>)

    @Query("UPDATE chats SET runtimeId = :runtimeId WHERE storedId = :storedId")
    suspend fun setRuntimeId(
        storedId: String,
        runtimeId: String?,
    )

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

    /** Lectura puntual del transcript (mismo orden que [observeMessages]). */
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY rowId ASC")
    suspend fun messagesFor(chatId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteForChat(chatId: String)
}
