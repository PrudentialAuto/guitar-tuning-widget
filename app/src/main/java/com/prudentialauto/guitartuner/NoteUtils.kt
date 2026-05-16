package com.prudentialauto.guitartuner

import kotlin.math.log2
import kotlin.math.roundToInt

object NoteUtils {

    private val NOTE_NAMES = arrayOf(
        "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
    )

    data class NoteInfo(
        val name: String,
        val frequency: Float,
        val cents: Float   // positive = sharp, negative = flat
    )

    fun fromFrequency(frequency: Float): NoteInfo {
        if (frequency <= 0f) return NoteInfo("--", 0f, 0f)

        // MIDI note number where A4 = 69 = 440 Hz
        val noteNumber = 12.0 * log2(frequency / 440.0) + 69.0
        val rounded = noteNumber.roundToInt()
        val cents = ((noteNumber - rounded) * 100.0).toFloat()
        val noteIndex = ((rounded % 12) + 12) % 12

        return NoteInfo(NOTE_NAMES[noteIndex], frequency, cents)
    }
}
