package com.example.sms.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 会话分类，用于 1b 的过滤 chips */
enum class MsgCategory { PERSONAL, TRANSACTION, PROMO, OTHER }

/** 单条消息的发送状态 */
enum class MsgStatus { RECEIVED, PENDING, SENT, DELIVERED, FAILED }

/** 消息类型 */
enum class MsgType { TEXT, IMAGE, VOICE }

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val threadId: Long,
    /** 多个收件人用 ";" 分隔 */
    val address: String,
    val displayName: String,
    val snippet: String = "",
    val snippetIsImage: Boolean = false,
    val lastTime: Long = 0L,
    val unreadCount: Int = 0,
    val category: MsgCategory = MsgCategory.OTHER,
    val pinned: Boolean = false,
    val blocked: Boolean = false,
    val muted: Boolean = false,
    val draft: String = "",
)

@Entity(
    tableName = "messages",
    indices = [Index("threadId"), Index("time"), Index(value = ["systemId"], unique = true)],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long,
    val address: String,
    val body: String,
    val time: Long,
    val outgoing: Boolean,
    val read: Boolean = false,
    val status: MsgStatus = MsgStatus.RECEIVED,
    val type: MsgType = MsgType.TEXT,
    /** 图片 / 语音的本地 uri */
    val attachmentUri: String? = null,
    val durationMs: Long = 0L,
    /** 表情回应，例如 "❤️" */
    val reaction: String? = null,
    /** 系统短信库 _id，用于导入去重；App 自建消息为 null */
    val systemId: Long? = null,
    val errorMessage: String? = null,
    /** 长短信分段状态，只有全部分段成功后才显示已发送/已送达。 */
    val partCount: Int = 1,
    val sentParts: Int = 0,
    val deliveredParts: Int = 0,
)

/** 已拦截消息计数用 */
@Entity(tableName = "blocked_log")
data class BlockedLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val body: String,
    val time: Long,
)
