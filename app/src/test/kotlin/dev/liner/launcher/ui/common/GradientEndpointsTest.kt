package dev.liner.launcher.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gradient angle is measured clockwise from vertical: 0 runs top-to-bottom
 * (`from` at the top), 90 runs left-to-right and 180 runs bottom-to-top.
 *
 * These tests pin that convention, because the Appearance tab's angle slider
 * writes the angle straight into the document — if the geometry drifts, the
 * slider silently paints the wrong direction.
 */
class GradientEndpointsTest {

    private val tolerance = 1e-6f

    @Test
    fun `angle zero runs from the top to the bottom`() {
        val (start, end) = gradientEndpoints(0)

        assertEquals(0.5f, start.x, tolerance)
        assertEquals(0f, start.y, tolerance)
        assertEquals(0.5f, end.x, tolerance)
        assertEquals(1f, end.y, tolerance)
    }

    @Test
    fun `angle ninety runs from the left to the right`() {
        val (start, end) = gradientEndpoints(90)

        assertEquals(0f, start.x, tolerance)
        assertEquals(0.5f, start.y, tolerance)
        assertEquals(1f, end.x, tolerance)
        assertEquals(0.5f, end.y, tolerance)
    }

    @Test
    fun `angle one hundred and eighty runs from the bottom to the top`() {
        val (start, end) = gradientEndpoints(180)

        assertEquals(0.5f, start.x, tolerance)
        assertEquals(1f, start.y, tolerance)
        assertEquals(0.5f, end.x, tolerance)
        assertEquals(0f, end.y, tolerance)
    }

    @Test
    fun `the line always passes through the centre of the screen`() {
        for (angle in listOf(0, 45, 90, 135, 180, 225, 270, 315)) {
            val (start, end) = gradientEndpoints(angle)

            assertEquals("midpoint x at $angle", 0.5f, (start.x + end.x) / 2f, tolerance)
            assertEquals("midpoint y at $angle", 0.5f, (start.y + end.y) / 2f, tolerance)
        }
    }

    @Test
    fun `the line turns clockwise as the angle grows`() {
        // The direction of the line, from `from` towards `to`.
        val (topToBottomStart, topToBottomEnd) = gradientEndpoints(0)
        val (leftToRightStart, leftToRightEnd) = gradientEndpoints(90)
        val (bottomToTopStart, bottomToTopEnd) = gradientEndpoints(180)

        assertEquals(1f, topToBottomEnd.y - topToBottomStart.y, tolerance)
        assertEquals(1f, leftToRightEnd.x - leftToRightStart.x, tolerance)
        assertEquals(1f, bottomToTopStart.y - bottomToTopEnd.y, tolerance)
    }
}
