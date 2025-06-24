package com.codenzi.ceparsivi

import android.os.Parcelable
import androidx.annotation.StringRes
import kotlinx.parcelize.Parcelize

@Parcelize
data class ArchivedFile(
    val fileName: String,
    val filePath: String,
    val dateAdded: String,
    // DÜZELTME: Kategori bilgisi artık dile bağlı bir String değil,
    // dile bağlı olmayan bir kaynak ID'si (Int) olarak tutuluyor.
    // Bu, sıralamanın her zaman doğru çalışmasını garanti eder.
    @StringRes val categoryResId: Int,
    val size: Long
) : Parcelable
