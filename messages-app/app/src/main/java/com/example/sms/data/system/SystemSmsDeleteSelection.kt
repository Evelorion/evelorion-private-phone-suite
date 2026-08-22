package com.example.sms.data.system

internal data class DeleteSelection(
    val where: String,
    val args: Array<String>,
)

/** 只按系统短信 _id 构造删除条件，绝不按号码或正文做模糊删除。 */
internal object SystemSmsDeleteSelection {
    fun byIds(rawIds: Collection<Long>): DeleteSelection? {
        val ids = rawIds.filter { it >= 0L }.distinct()
        if (ids.isEmpty()) return null
        return DeleteSelection(
            where = "_id IN (${ids.joinToString(",") { "?" }})",
            args = ids.map(Long::toString).toTypedArray(),
        )
    }
}
