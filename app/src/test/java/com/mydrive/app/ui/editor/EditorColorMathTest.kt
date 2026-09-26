package com.mydrive.app.ui.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class EditorColorMathTest {

    @Test
    fun identityAdjustmentsProduceAnIdentityMatrix() {
        val matrix = EditorColorMath.combined(EditorAdjustments(), EditorFilter.ORIGINAL)
        assertTrue(EditorColorMath.isIdentity(matrix))
    }

    @Test
    fun brightnessContrastSaturationAndWarmthChangeTheMatrix() {
        val matrix = EditorColorMath.combined(
            EditorAdjustments(brightness = 0.2f, contrast = 0.3f, saturation = -0.4f, warmth = 0.5f),
            EditorFilter.ORIGINAL
        )
        assertFalse(EditorColorMath.isIdentity(matrix))
    }

    @Test
    fun filterPresetsUseNeutralNamesAndAreNotIdentity() {
        val names = EditorFilter.entries.map { it.displayName }
        assertTrue(names.contains("Original"))
        assertTrue(names.contains("Mono"))
        assertTrue(names.contains("Warm Glow"))
        assertFalse(names.any { it.contains("Clarendon", ignoreCase = true) })
        assertFalse(names.any { it.contains("Juno", ignoreCase = true) })
        assertFalse(names.any { it.contains("Ludwig", ignoreCase = true) })

        EditorFilter.entries.filter { it != EditorFilter.ORIGINAL }.forEach { filter ->
            assertFalse(filter.displayName, EditorColorMath.isIdentity(EditorColorMath.filterMatrix(filter)))
        }
    }

    @Test
    fun matrixMultiplyKeepsIdentityStable() {
        val identity = EditorColorMath.identity()
        val product = EditorColorMath.multiply(identity, identity)
        assertTrue(product.indices.all { abs(product[it] - identity[it]) < 0.0001f })
    }
}
