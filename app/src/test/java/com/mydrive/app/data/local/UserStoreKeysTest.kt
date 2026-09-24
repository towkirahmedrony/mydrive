package com.mydrive.app.data.local

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserStoreKeysTest {

    @Test
    fun persistentKeysAreUserScoped() {
        assertTrue(UserStoreKeys.hidden("user-a").contains("user-a"))
        assertTrue(UserStoreKeys.cloud("user-a").contains("user-a"))
        assertTrue(UserStoreKeys.favorites("user-a").contains("user-a"))
        assertTrue(UserStoreKeys.syncRecords("user-a").contains("user-a"))
        assertTrue(UserStoreKeys.mediaSyncCursor("user-a").contains("user-a"))
        assertNotEquals(UserStoreKeys.cloud("user-a"), UserStoreKeys.cloud("user-b"))
        assertNotEquals(UserStoreKeys.favorites("user-a"), UserStoreKeys.favorites("user-b"))
        assertNotEquals(UserStoreKeys.syncRecords("user-a"), UserStoreKeys.syncRecords("user-b"))
        assertNotEquals(UserStoreKeys.hidden("user-a"), UserStoreKeys.hidden("user-b"))
        assertNotEquals(UserStoreKeys.mediaSyncCursor("user-a"), UserStoreKeys.mediaSyncCursor("user-b"))
        // The synchronization cursor must not share a key with any other store.
        assertNotEquals(UserStoreKeys.mediaSyncCursor("user-a"), UserStoreKeys.syncRecords("user-a"))
    }
}
