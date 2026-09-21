package com.almus.studio.audio

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager

/** Android MIDI 2.0-ready discovery foundation. Playback scheduling is deliberately kept separate. */
object MidiEngine {
    data class Device(
        val id: Int,
        val name: String,
        val manufacturer: String,
        val product: String,
        val inputPorts: Int,
        val outputPorts: Int
    )

    fun discoverDevices(context: Context): List<Device> {
        val manager = context.getSystemService(MidiManager::class.java) ?: return emptyList()
        return manager.devices.map { info ->
            Device(
                id = info.id,
                name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI Device ${info.id}",
                manufacturer = info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER) ?: "",
                product = info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "",
                inputPorts = info.inputPortCount,
                outputPorts = info.outputPortCount
            )
        }
    }
}
