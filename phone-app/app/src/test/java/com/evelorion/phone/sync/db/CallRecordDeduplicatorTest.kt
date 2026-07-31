package com.evelorion.phone.sync.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CallRecordDeduplicatorTest {

    @Test
    fun stableUuidIsRepeatableForTheSameCall() {
        val identity = CallRecordDeduplicator.identity(
            number = "+86 138-0013-8000",
            kind = "incoming",
            startedAt = 1_722_222_222_000,
            durationSeconds = 42,
        )

        assertEquals(
            CallRecordDeduplicator.stableUuid(identity),
            CallRecordDeduplicator.stableUuid(identity),
        )
        assertNotEquals(
            CallRecordDeduplicator.stableUuid(identity),
            CallRecordDeduplicator.stableUuid("$identity|different"),
        )
    }

    @Test
    fun cleanKeepsOneRecordAndTombstonesSyncedDuplicates() {
        val dao = FakeCallDao(
            mutableListOf(
                record("local", rev = 0, dirty = true),
                record("cloud-old", rev = 2, dirty = false),
                record("cloud-new", rev = 4, dirty = false),
            )
        )

        assertEquals(2, CallRecordDeduplicator.clean(dao))
        assertEquals(listOf("cloud-new"), dao.recent().map { it.uuid })
        assertEquals(
            listOf("cloud-old"),
            dao.records.filter { it.deletedLocally }.map { it.uuid },
        )
    }

    private fun record(uuid: String, rev: Int, dirty: Boolean) = CallRecordEntity(
        uuid = uuid,
        number = "13800138000",
        name = "Test",
        kind = "incoming",
        startedAt = 1_722_222_222_000,
        durationSeconds = 42,
        rev = rev,
        dirty = dirty,
    )

    private class FakeCallDao(
        val records: MutableList<CallRecordEntity>,
    ) : CallDao {
        private var syncState: CallSyncStateEntity? = null

        override fun recent(limit: Int): List<CallRecordEntity> =
            records.filterNot { it.deletedLocally }
                .sortedByDescending { it.startedAt }
                .take(limit)

        override fun byUuid(uuid: String): CallRecordEntity? =
            records.firstOrNull { it.uuid == uuid }

        override fun pending(limit: Int): List<CallRecordEntity> =
            records.filter { it.dirty || it.deletedLocally }.take(limit)

        override fun countPending(): Int =
            records.count { it.dirty || it.deletedLocally }

        override fun newestStart(): Long? = records.maxOfOrNull { it.startedAt }

        override fun upsert(record: CallRecordEntity) {
            records.removeAll { it.uuid == record.uuid }
            records.add(record)
        }

        override fun upsertAll(records: List<CallRecordEntity>) {
            records.forEach(::upsert)
        }

        override fun deleteByUuid(uuid: String) {
            records.removeAll { it.uuid == uuid }
        }

        override fun state(): CallSyncStateEntity? = syncState

        override fun putState(state: CallSyncStateEntity) {
            syncState = state
        }
    }
}
