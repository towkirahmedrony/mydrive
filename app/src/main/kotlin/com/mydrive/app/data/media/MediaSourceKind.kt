package com.mydrive.app.data.media

/**
 * Where a media item's bytes can actually be read from.
 *
 * A Cloudinary delivery URL and a `content://` MediaStore row are two entirely
 * different kinds of source: `ContentResolver` speaks the second and cannot open
 * the first. Passing a remote URL into it produced
 * `IllegalStateException: No content provider: https://res.cloudinary.com/...`
 * from `BackupRepository.processOne`, so every upload source is classified here
 * before anything is opened, and only [MEDIASTORE], [LOCAL_FILE] and
 * [APP_PRIVATE_FILE] may ever reach `ContentResolver`.
 *
 * The classification is done on the URI string alone — no `android.net.Uri`, no
 * content resolution — so it is a pure decision that can be unit tested and that
 * cannot itself fail on the very input it is meant to reject.
 */
enum class MediaSourceKind {
    /** `content://…` — a MediaStore or other content-provider row. */
    MEDIASTORE,

    /** `file://…` — a local filesystem path carrying the file scheme. */
    LOCAL_FILE,

    /** A bare absolute path — a real local file, not a content provider. */
    APP_PRIVATE_FILE,

    /** `http(s)://…` — the cloud copy. A download source, never an upload source. */
    REMOTE,

    /** Blank, malformed or an unrecognised scheme. Never opened. */
    UNKNOWN;

    /**
     * Whether this source may be handed to `ContentResolver` as the upload body.
     *
     * [REMOTE] is deliberately false: the cloud copy already exists, so the correct
     * answer for such an item is "no upload needed" — never "download it and upload
     * it again".
     */
    val isLocalUploadSource: Boolean
        get() = this == MEDIASTORE || this == LOCAL_FILE || this == APP_PRIVATE_FILE

    /** The persisted-queue / log label for this kind. */
    val label: String
        get() = name

    companion object {
        /**
         * Classifies [rawUri] as an upload-source candidate.
         *
         * @param originLocal whether the item claims to be a device-local media
         *   item. A remote scheme is [REMOTE] either way: `originLocal` can only
         *   ever *downgrade* an ambiguous source to [UNKNOWN], so a stale flag can
         *   never turn a cloud URL into an upload source.
         */
        fun of(rawUri: String?, originLocal: Boolean = true): MediaSourceKind {
            val raw = rawUri?.trim().orEmpty()
            if (raw.isEmpty()) return UNKNOWN
            val scheme = raw.substringBefore(':', missingDelimiterValue = "")
                .lowercase()
                .takeIf { candidate -> candidate.isNotBlank() && candidate.none { it == '/' || it == ' ' } }
            return when (scheme) {
                "content", "android.resource" -> MEDIASTORE
                "http", "https" -> REMOTE
                "file" -> LOCAL_FILE
                // No usable scheme: only an absolute path can be a local source,
                // and only for an item that claims to be local.
                else -> if (raw.startsWith("/")) {
                    if (originLocal) APP_PRIVATE_FILE else UNKNOWN
                } else {
                    UNKNOWN
                }
            }
        }
    }
}

/** Convenience alias so call sites read as a plain function. */
fun classifyMediaSource(rawUri: String?, originLocal: Boolean = true): MediaSourceKind =
    MediaSourceKind.of(rawUri, originLocal)
