package com.example.ipc

import android.os.Parcel
import android.os.Parcelable

/**
 * Parcelable configuration mirroring the C++ PipelineConfig constructor.
 * Fields and defaults correspond 1:1 for safe cross-process transport.
 */
class LLMConfig(
    val modelPath: String,
    val backendType: String = "",
    val tokenizerType: String = "",
    val tokenizerPath: String = "",
    val chatTemplateFile: String = "",
    val maxContextSize: Int = 0,
    val maxOutputSize: Int = 0,
    val forceSliceImage: Boolean = false,
    val addGenerationPrompt: Boolean = true,
    val setEnableThinking: Boolean = false,
    val enableThinking: Boolean = false,
    val draftModelPath: String = ""
) : Parcelable {
    constructor(parcel: Parcel) : this(
        modelPath = parcel.readString().orEmpty(),
        backendType = parcel.readString().orEmpty(),
        tokenizerType = parcel.readString().orEmpty(),
        tokenizerPath = parcel.readString().orEmpty(),
        chatTemplateFile = parcel.readString().orEmpty(),
        maxContextSize = parcel.readInt(),
        maxOutputSize = parcel.readInt(),
        forceSliceImage = parcel.readByte() != 0.toByte(),
        addGenerationPrompt = parcel.readByte() != 0.toByte(),
        setEnableThinking = parcel.readByte() != 0.toByte(),
        enableThinking = parcel.readByte() != 0.toByte(),
        draftModelPath = parcel.readString().orEmpty()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(modelPath)
        parcel.writeString(backendType)
        parcel.writeString(tokenizerType)
        parcel.writeString(tokenizerPath)
        parcel.writeString(chatTemplateFile)
        parcel.writeInt(maxContextSize)
        parcel.writeInt(maxOutputSize)
        parcel.writeByte(if (forceSliceImage) 1.toByte() else 0.toByte())
        parcel.writeByte(if (addGenerationPrompt) 1.toByte() else 0.toByte())
        parcel.writeByte(if (setEnableThinking) 1.toByte() else 0.toByte())
        parcel.writeByte(if (enableThinking) 1.toByte() else 0.toByte())
        parcel.writeString(draftModelPath)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<LLMConfig> {
        override fun createFromParcel(parcel: Parcel): LLMConfig = LLMConfig(parcel)
        override fun newArray(size: Int): Array<LLMConfig?> = arrayOfNulls(size)
    }
}

