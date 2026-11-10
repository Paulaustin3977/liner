package dev.liner.launcher.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `angle zero runs top to bottom`() {
        val (start, end) = gradientEndpoints(angleDegrees = 0, width = 100f, height = 200f)
        assertEquals(start.x, end.x, 0.001f)
        assertTrue("the gradient must start at the top", start.y < end.y)
    }

    @Test
    fun `angle ninety runs left to right`() {
        val (start, end) = gradientEndpoints(angleDegrees = 90, width = 100f, height = 200f)
        assertEquals(start.y, end.y, 0.001f)
        assertTrue("the gradient must start at the left", start.x < end.x)
    }

    @Test
    fun `angle one eighty runs bottom to top`() {
        val (start, end) = gradientEndpoints(angleDegrees = 180, width = 100f, height = 200f)
        assertEquals(start.x, end.x, 0.001f)
        assertTrue("the gradient must start at the bottom", start.y > end.y)
    }

    @Test
    fun `the gradient passes through the centre`() {
        val (start, end) = gradientEndpoints(angleDegrees = 37, width = 100f, height = 200f)
        assertEquals(50f, (start.x + end.x) / 2f, 0.001f)
        assertEquals(100f, (start.y + end.y) / 2f, 0.001f)
    }

    @Test
    fun `the gradient turns clockwise`() {
        // The end point must sit right of and below the start point at an
        // intermediate angle. The cardinal cases above cannot catch a
        // flipped sign, which would mirror every non-cardinal angle.
        val (start, end) = gradientEndpoints(angleDegrees = 45, width = 100f, height = 200f)
        assertTrue("the gradient must turn to the right", end.x > start.x)
        assertTrue("the gradient must turn downwards", end.y > start.y)
    }

    @Test
    fun `the angle is preserved on a wide screen`() {
        // Scaling the two axes independently would skew the angle; the
        // endpoints must keep the requested direction on any aspect ratio.
        val (start, end) = gradientEndpoints(angleDegrees = 45, width = 400f, height = 100f)
        val dx = end.x - start.x
        val dy = end.y - start.y
        assertEquals(1f, dx / dy, 0.01f)
    }
}
