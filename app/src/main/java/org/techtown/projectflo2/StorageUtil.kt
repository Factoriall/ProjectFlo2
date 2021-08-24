package org.techtown.projectflo2

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.ByteArrayOutputStream
import java.lang.reflect.Type
import java.util.*
import kotlin.collections.ArrayList
import android.graphics.BitmapFactory
import android.util.Base64.*
import android.util.Log


class StorageUtil(private val context: Context) {
    private val STORAGE = "org.techtown.projectflo2.STORAGE"
    private var preferences: SharedPreferences? = null

    fun storeAudio(arrayList: ArrayList<Music>) {
        preferences = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)
        val editor = preferences!!.edit()
        val gson = Gson()

        val json: String = gson.toJson(arrayList)
        editor.putString("audioArrayList", json)
        editor.apply()
    }



    fun loadAudio(): ArrayList<Music> {
        preferences = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)
        val gson = Gson()
        val json = preferences!!.getString("audioArrayList", null)
        val type: Type = object : TypeToken<ArrayList<Music>>(){}.type
        return gson.fromJson(json, type)
    }

    fun storeImages(bmpList: ArrayList<Bitmap>){
        preferences = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)
        val editor = preferences!!.edit()


        val stream = ByteArrayOutputStream()
        for((idx, bmp) in bmpList.withIndex()) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val b: ByteArray = stream.toByteArray()
            val encodedImage: String = encodeToString(b, DEFAULT)
            editor.putString("albumImage$idx", encodedImage)
        }
        editor.apply()

    }

    fun loadImage(idx : Int): Bitmap{
        preferences = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)
        val previouslyEncodedImage: String = preferences!!.getString("albumImage$idx", "")!!
        val b: ByteArray = decode(previouslyEncodedImage, DEFAULT)
        return BitmapFactory.decodeByteArray(b, 0, b.size)
    }

    fun storePlayingInfo(isPlaying: Boolean, seekTime: Int){
        preferences = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)
        val editor = preferences!!.edit()
        editor.putBoolean("isPlaying", isPlaying)
        editor.putInt("seekTime", seekTime)
        editor.apply()
    }

    fun loadIsPlaying() : Boolean{
        preferences = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)
        return preferences!!.getBoolean("isPlaying", false) //return -1 if no data found
    }

    fun loadSeekTime() : Int{
        preferences = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)
        return preferences!!.getInt("seekTime", 0)
    }

    fun storeAudioIndex(index: Int) {
        preferences = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)
        val editor = preferences!!.edit()
        editor.putInt("audioIndex", index)
        editor.apply()
    }

    fun loadAudioIndex(): Int {
        preferences = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)
        return preferences!!.getInt("audioIndex", -1) //return -1 if no data found
    }

    fun clearCachedAudioPlaylist() {
        preferences = context.getSharedPreferences(STORAGE, Context.MODE_PRIVATE)
        val editor = preferences!!.edit()
        editor.clear()
        editor.commit()
    }
}