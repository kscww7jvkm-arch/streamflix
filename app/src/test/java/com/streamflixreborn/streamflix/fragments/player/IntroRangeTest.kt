package com.streamflixreborn.streamflix.fragments.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroRangeTest {
    private val range = IntroRange(startMs = 3_000L, endMs = 88_000L)

    @Test fun `position before intro start is hidden`() = assertFalse(range.contains(2_999L))
    @Test fun `position exactly at intro start is visible`() = assertTrue(range.contains(3_000L))
    @Test fun `position inside intro range is visible`() = assertTrue(range.contains(45_000L))
    @Test fun `position immediately before intro end is visible`() = assertTrue(range.contains(87_999L))
    @Test fun `position exactly at intro end is hidden`() = assertFalse(range.contains(88_000L))
    @Test fun `position after intro end is hidden`() = assertFalse(range.contains(100_000L))

    @Test fun `seek destination is the absolute intro end`() {
        val currentPosition = 40_000L
        assertEquals(88_000L, range.seekDestinationMs)
        assertFalse(range.seekDestinationMs == currentPosition + (range.endMs - range.startMs))
    }
}
