package com.codenzi.ceparsivi

import android.os.Parcelable
import androidx.annotation.StringRes
import kotlinx.parcelize.Parcelize

@Parcelize
data class ArchivedFile(
    val fileName: String,
    val filePath: String,
    val dateAdded: String,

    @StringRes val categoryResId: Int,
    val size: Long
) : Parcelable
