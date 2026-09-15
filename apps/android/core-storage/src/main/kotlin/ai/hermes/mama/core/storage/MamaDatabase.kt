package ai.hermes.mama.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Caché local de la app (B6): lista de chats + transcripts.
 *
 * El esquema se exporta a `schemas/` (plugin `androidx.room` en el build) para
 * anclar las migraciones futuras. La instancia la crea quien compone la app
 * (Hilt llega con el wiring de :app); para tests JVM hay
 * `Room.inMemoryDatabaseBuilder` + Robolectric.
 */
@Database(
    entities = [ChatEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MamaDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    abstract fun messageDao(): MessageDao

    companion object {
        const val DATABASE_NAME = "mama.db"

        /** Instancia de app (persistente). Los tests usan `Room.inMemoryDatabaseBuilder` directamente. */
        fun create(context: Context): MamaDatabase =
            Room
                .databaseBuilder(context.applicationContext, MamaDatabase::class.java, DATABASE_NAME)
                .build()
    }
}
