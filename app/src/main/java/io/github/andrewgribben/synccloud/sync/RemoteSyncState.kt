package io.github.andrewgribben.synccloud.sync

/**
 * Where a local calendar/address book is in relation to the server: either a WebDAV sync-token
 * (RFC 6578 incremental sync) or a CTag (whole-collection change tag, used as the ETag-comparison
 * fallback). Persisted as a single string (synctools' per-calendar sync state field, or the
 * address book's [android.provider.ContactsContract.SyncState]).
 */
sealed class RemoteSyncState {
    abstract val value: String

    data class Token(override val value: String) : RemoteSyncState()
    data class CTag(override val value: String) : RemoteSyncState()

    fun encode(): String = when (this) {
        is Token -> "$PREFIX_TOKEN$value"
        is CTag -> "$PREFIX_CTAG$value"
    }

    companion object {
        private const val PREFIX_TOKEN = "token:"
        private const val PREFIX_CTAG = "ctag:"

        fun decode(raw: String?): RemoteSyncState? = when {
            raw == null -> null
            raw.startsWith(PREFIX_TOKEN) -> Token(raw.removePrefix(PREFIX_TOKEN))
            raw.startsWith(PREFIX_CTAG) -> CTag(raw.removePrefix(PREFIX_CTAG))
            else -> null // unknown/corrupt state: treat as "unknown", forces a full listing
        }
    }
}

/** Which strategy to use to catch up with the server, decided once per sync run. */
enum class SyncPlan {
    /** Continue (or start) RFC 6578 `sync-collection`; the server tells us exactly what changed. */
    SYNC_COLLECTION,

    /** List every remote item and compare ETags (used when the server rejected our sync-token, or never gave us one). */
    FULL_LISTING;

    companion object {
        /**
         * @param lastState           sync state persisted from the previous successful run, if any
         * @param syncTokenRejected   whether this run's `sync-collection` REPORT has already failed
         *                            (e.g. HTTP 400 `valid-sync-token`) and must not be retried
         */
        fun decide(lastState: RemoteSyncState?, syncTokenRejected: Boolean): SyncPlan =
            if (lastState is RemoteSyncState.Token && !syncTokenRejected)
                SYNC_COLLECTION
            else
                FULL_LISTING
    }
}
