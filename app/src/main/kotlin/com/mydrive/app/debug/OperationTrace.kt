package com.mydrive.app.debug

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object OperationTrace {
    private val ids = ConcurrentHashMap<String, String>()

    fun start(localMediaId: String): String {
        val id = newId()
        ids[localMediaId] = id
        return id
    }

    fun idFor(localMediaId: String): String =
        ids.getOrPut(localMediaId) { newId() }

    fun existing(localMediaId: String): String? = ids[localMediaId]

    fun bind(localMediaId: String, operationId: String) {
        if (localMediaId.isNotBlank() && operationId.isNotBlank()) {
            ids[localMediaId] = operationId
        }
    }

    fun clear(localMediaId: String) {
        ids.remove(localMediaId)
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
}
