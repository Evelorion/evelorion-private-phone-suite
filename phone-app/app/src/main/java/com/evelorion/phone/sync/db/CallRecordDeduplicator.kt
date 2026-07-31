package com.evelorion.phone.sync.db

import java.nio.charset.StandardCharsets
import java.util.UUID

object CallRecordDeduplicator {

    fun identity(
        number: String,
        kind: String,
        startedAt: Long,
        durationSeconds: Int,
    ): String {
        val normalizedNumber = number.filter(Char::isDigit).takeLast(15)
        return "$normalizedNumber|$kind|$startedAt|$durationSeconds"
    }

    fun identity(record: CallRecordEntity): String =
        identity(record.number, record.kind, record.startedAt, record.durationSeconds)

    fun stableUuid(identity: String): String =
        UUID.nameUUIDFromBytes(
            "evelorion-system-call-v1|$identity".toByteArray(StandardCharsets.UTF_8)
        ).toString()

    /**
     * Keeps one visible row for each exact call event. Synced extras become
     * tombstones so the server cannot send them back on the next pull.
     */
    fun clean(dao: CallDao): Int {
        var removed = 0
        dao.recent(10_000)
            .groupBy(::identity)
            .values
            .filter { it.size > 1 }
            .forEach { duplicates ->
                val keep = duplicates.maxWithOrNull(
                    compareBy<CallRecordEntity>(
                        { if (it.rev > 0) 1 else 0 },
                        { it.rev },
                        { if (!it.dirty) 1 else 0 },
                    )
                ) ?: return@forEach

                duplicates.asSequence()
                    .filter { it.uuid != keep.uuid }
                    .forEach { extra ->
                        if (extra.rev == 0) {
                            dao.deleteByUuid(extra.uuid)
                        } else {
                            dao.upsert(extra.copy(deletedLocally = true, dirty = true))
                        }
                        removed++
                    }
            }
        return removed
    }
}
