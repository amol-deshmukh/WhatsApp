package com.example.whatsapp.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.whatsapp.domain.model.TransferState
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query(
        """
        SELECT c.id, c.title, c.avatar_color,
               m.type AS last_type, m.text AS last_text, m.created_at AS last_at,
               m.is_outgoing AS last_outgoing, m.status AS last_status,
               (SELECT COUNT(*) FROM messages u
                 WHERE u.conversation_id = c.id AND u.is_outgoing = 0 AND u.status < 3) AS unread_count
        FROM conversations c
        LEFT JOIN messages m ON m.id = (
            SELECT id FROM messages WHERE conversation_id = c.id ORDER BY created_at DESC LIMIT 1
        )
        ORDER BY COALESCE(m.created_at, c.created_at) DESC
        """
    )
    fun observeConversations(): Flow<List<ConversationRow>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observe(id: String): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(conversations: List<ConversationEntity>)

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int
}

@Dao
interface MessageDao {

    /** Returns -1 when the id already exists: incoming duplicates (at-least-once delivery) are dropped here. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at DESC, id DESC")
    fun pagingSource(conversationId: String): PagingSource<Int, MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE id = :id")
    fun observe(id: String): Flow<MessageEntity?>

    /** The outbox: outgoing messages not yet acked whose media (if any) is already uploaded. */
    @Query(
        """
        SELECT * FROM messages
        WHERE is_outgoing = 1 AND status = 0 AND (type = 'TEXT' OR media_remote_url IS NOT NULL)
        ORDER BY created_at
        """
    )
    fun observeOutbox(): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE is_outgoing = 1 AND status = 0")
    fun observeUnsentCount(): Flow<Int>

    /** Forward-only status update; FAILED (-1) rows are never resurrected by server events. */
    @Query("UPDATE messages SET status = :status WHERE id = :id AND status >= 0 AND status < :status")
    suspend fun advanceStatus(id: String, status: Int)

    @Query(
        """
        UPDATE messages SET server_seq = :seq, status = MAX(status, 1)
        WHERE id = :id AND status >= 0
        """
    )
    suspend fun markAcked(id: String, seq: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId AND is_outgoing = 0 AND status < 3")
    fun observeUnreadCount(conversationId: String): Flow<Int>

    @Query("SELECT id FROM messages WHERE conversation_id = :conversationId AND is_outgoing = 0 AND status < 3")
    suspend fun unreadIncomingIds(conversationId: String): List<String>

    @Query("UPDATE messages SET status = 3 WHERE id IN (:ids)")
    suspend fun markRead(ids: List<String>)

    @Transaction
    suspend fun consumeUnread(conversationId: String): List<String> {
        val ids = unreadIncomingIds(conversationId)
        if (ids.isNotEmpty()) markRead(ids)
        return ids
    }

    @Query("UPDATE messages SET transfer_state = :state WHERE id = :id")
    suspend fun setTransferState(id: String, state: TransferState)

    @Query("UPDATE messages SET media_remote_url = :remoteUrl, transfer_state = 'NONE' WHERE id = :id")
    suspend fun setUploaded(id: String, remoteUrl: String)

    @Query("UPDATE messages SET media_local_path = :path, transfer_state = 'NONE' WHERE id = :id")
    suspend fun setDownloaded(id: String, path: String)

    @Query("UPDATE messages SET status = -1, transfer_state = :state WHERE id = :id AND status = 0")
    suspend fun markFailed(id: String, state: TransferState)

    @Query(
        """
        UPDATE messages SET status = 0,
            transfer_state = CASE WHEN type != 'TEXT' AND media_remote_url IS NULL THEN 'UPLOAD_PENDING' ELSE 'NONE' END
        WHERE id = :id AND status = -1
        """
    )
    suspend fun resetForRetry(id: String)
}

@Dao
interface SyncStateDao {
    @Query("SELECT last_seq FROM sync_state WHERE id = 0")
    suspend fun lastSeq(): Long?

    @Upsert
    suspend fun upsert(state: SyncStateEntity)
}
