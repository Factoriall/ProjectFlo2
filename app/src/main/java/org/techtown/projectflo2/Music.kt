package org.techtown.projectflo2

import android.graphics.Bitmap
import java.io.Serializable

data class Music(val url : String,
                 val songName : String,
                 val singerName : String,
                 val albumName : String,
                 val musicLyrics: List<MusicLyrics>,
                 val duration: Int,
                 val fileUrl : String) : Serializable