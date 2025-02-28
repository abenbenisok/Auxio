/*
 * Copyright (c) 2024 Auxio Project
 * TagLibJNI.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.musikr.metadata

import android.content.Context
import android.net.Uri

internal object TagLibJNI {
    init {
        System.loadLibrary("taglib_jni")
    }

    /**
     * Open a file and extract a tag.
     *
     * Note: This method is blocking and should be handled as such if calling from a coroutine.
     */
    fun open(context: Context, ref: FileRef): Metadata? {
        val inputStream = AndroidInputStream(context, ref)
        val tag = openNative(inputStream)
        inputStream.close()
        return tag
    }

    private external fun openNative(ioStream: AndroidInputStream): Metadata?
}

internal data class FileRef(val fileName: String, val uri: Uri)
