package com.example.ceparsivi

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize // Parcelize anotasyonu eklendi
data class ArchivedFile(
    val fileName: String,
    val filePath: String,
    val dateAdded: String,
    val category: String,
    val size: Long // Dosya boyutu eklendi
) : Parcelable // Parcelable arayüzü eklendi