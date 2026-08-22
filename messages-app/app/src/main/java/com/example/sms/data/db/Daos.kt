package com.example.sms.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query(
        """
        SELECT * FROM conversations
        WHERE blocked = 0
          AND (draft != '' OR EXISTS (
              SELECT 1 FROM messages WHERE messages.threadId = conversations.threadId
          ))
        ORDER BY pinned DESC, lastTime DESC
        """
    )
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE threadId = :threadId")
    fun observeById(threadId: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE threadId = :threadId")
    suspend fun getById(threadId: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE address = :address LIMIT 1")
    suspend fun getByAddress(address: String): ConversationEntity?

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("SELECT COALESCE(MAX(threadId), 0) + 1 FROM conversations")
    suspend fun nextThreadId(): Long

    @Query("UPDATE conversations SET unreadCount = 0 WHERE threadId = :threadId")
    suspend fun clearUnread(threadId: Long)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE unreadCount > 0")
    suspend fun clearAllUnread()

    @Query("UPDATE conversations SET draft = :draft WHERE threadId = :threadId")
    suspend fun setDraft(threadId: Long, draft: String)

    @Query("UPDATE conversations SET pinned = :pinned WHERE threadId = :threadId")
    suspend fun setPinned(threadId: Long, pinned: Boolean)

    @Query("UPDATE conversations SET muted = :muted WHERE threadId = :threadId")
    suspend fun setMuted(threadId: Long, muted: Boolean)

    @Query("UPDATE conversations SET blocked = :blocked WHERE threadId = :threadId")
    suspend fun setBlocked(threadId: Long, blocked: Boolean)

    @Query("DELETE FROM conversations WHERE threadId = :threadId")
    suspend fun delete(threadId: Long)

    @Query(
        """
        UPDATE conversations SET
            snippet = :snippet,
            snippetIsImage = :isImage,
            lastTime = :time,
            unreadCount = (SELECT COUNT(*) FROM messages WHERE threadId = :threadId AND read = 0 AND outgoing = 0)
        WHERE threadId = :threadId
        """
    )
    suspend fun refreshSummary(threadId: Long, snippet: String, isImage: Boolean, time: Long)
}

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE threadId = :threadId ORDER BY time ASC, id ASC")
    fun observeThread(threadId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: Long): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("UPDATE messages SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: MsgStatus, error: String?)

    @Query(
        """
        UPDATE messages SET status = 'FAILED', errorMessage = :error
        WHERE id = :id AND status = 'PENDING'
        """
    )
    suspend fun markFailedIfPending(id: Long, error: String): Int

    @Query(
        """
        UPDATE messages SET
            partCount = :partCount,
            sentParts = 0,
            deliveredParts = 0
        WHERE id = :id
        """
    )
    suspend fun prepareMultipart(id: Long, partCount: Int)

    @Query(
        """
        UPDATE messages SET sentParts = MIN(sentParts + 1, partCount)
        WHERE id = :id AND status != 'FAILED'
        """
    )
    suspend fun incrementSentParts(id: Long)

    @Query(
        """
        UPDATE messages SET status = 'SENT', errorMessage = NULL
        WHERE id = :id AND status = 'PENDING' AND sentParts >= partCount
        """
    )
    suspend fun markSentWhenComplete(id: Long)

    @Query(
        """
        UPDATE messages SET deliveredParts = MIN(deliveredParts + 1, partCount)
        WHERE id = :id AND status != 'FAILED'
        """
    )
    suspend fun incrementDeliveredParts(id: Long)

    @Query(
        """
        UPDATE messages SET status = 'DELIVERED', errorMessage = NULL
        WHERE id = :id AND status = 'SENT' AND deliveredParts >= partCount
        """
    )
    suspend fun markDeliveredWhenComplete(id: Long)

    @Query("UPDATE messages SET reaction = :reaction WHERE id = :id")
    suspend fun setReaction(id: Long, reaction: String?)

    @Query("UPDATE messages SET read = 1 WHERE threadId = :threadId AND read = 0")
    suspend fun markThreadRead(threadId: Long)

    @Query("UPDATE messages SET read = 1 WHERE read = 0 AND outgoing = 0")
    suspend fun markAllRead()

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM messages WHERE threadId = :threadId")
    suspend fun deleteThread(threadId: Long)

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("SELECT * FROM messages WHERE body LIKE '%' || :q || '%' ORDER BY time DESC LIMIT 100")
    suspend fun search(q: String): List<MessageEntity>

    /** 最近 30 天里含验证码/取件码的消息，用于「从信息中提取」卡片 */
    @Query("SELECT * FROM messages WHERE outgoing = 0 AND time > :since ORDER BY time DESC LIMIT 200")
    suspend fun recentIncoming(since: Long): List<MessageEntity>
}

@Dao
interface BlockedLogDao {
    @Insert suspend fun insert(entity: BlockedLogEntity)

    @Query("SELECT COUNT(*) FROM blocked_log WHERE time > :since")
    fun observeCountSince(since: Long): Flow<Int>
}
