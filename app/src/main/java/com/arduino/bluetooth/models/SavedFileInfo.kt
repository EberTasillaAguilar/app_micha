package com.arduino.bluetooth.models

import java.io.File

data class SavedFileInfo(
    val file: File,
    val name: String,
    val sizeText: String,
    val dateText: String
)
