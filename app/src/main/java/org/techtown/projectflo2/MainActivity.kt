package org.techtown.projectflo2

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Main
import org.json.JSONObject
import org.json.JSONTokener
import org.techtown.projectflo2.MediaPlayerService.Companion.CHANNEL_ID
import java.net.MalformedURLException
import java.net.URL

class MainActivity : AppCompatActivity(),PlayerControl {
    private val urlName =
        "https://grepp-programmers-challenges.s3.ap-northeast-2.amazonaws.com/2020-flo/song.json"
    companion object {
        const val Broadcast_PLAY = "org.techtown.projectflo2.PLAY"
        const val Broadcast_RESUME = "org.techtown.projectflo2.RESUME"
        const val Broadcast_PAUSE = "org.techtown.projectflo2.PAUSE"
        const val Broadcast_SEEK_TO_PLAY = "org.techtown.projectflo2.SEEK_TO_PLAY"
        const val Broadcast_SEEK_TO_PAUSE = "org.techtown.projectflo2.SEEK_TO_PAUSE"

        var player : MediaPlayerService? = null
        var serviceBound = false
        var isPlaying = false
        var seekTime : Int = 0
        var isPrepared = false
        var musicIdx : Int = 0

        var musicList = arrayListOf<Music>()
        var bitmapList = arrayListOf<Bitmap>()
    }
    var updateJob: Job? = null

    private lateinit var songTitle: TextView
    private lateinit var albumImage: ImageView
    private lateinit var albumName: TextView
    private lateinit var singerName: TextView
    private lateinit var songNow: TextView
    private lateinit var songLength: TextView
    private lateinit var musicSeekBar: SeekBar
    private lateinit var mainLyrics: TextView
    private lateinit var nextLyrics: TextView
    private lateinit var controlButton: ImageView
    private lateinit var lyricsLayout: ConstraintLayout
    private lateinit var notificationManager : NotificationManager
    private lateinit var activityResultLauncher : ActivityResultLauncher<Intent>

    private lateinit var image : Bitmap

    //serviceBound??? ?????? ????????? ??????
    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        Log.d("LifecycleCheck", "onSaveInstanceState")

        savedInstanceState.putBoolean("ServiceState", serviceBound)
        super.onSaveInstanceState(savedInstanceState)
        updateJob?.cancel()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        Log.d("LifecycleCheck", "onRestoreInstanceState")

        serviceBound = savedInstanceState.getBoolean("ServiceState")
        if (isPlaying) controlButton.setImageResource(R.drawable.ic_pause)
        else controlButton.setImageResource(R.drawable.ic_play)
        musicList = StorageUtil(applicationContext).loadAudio()
        image = StorageUtil(applicationContext).loadImage(0)
        musicSeekBar.progress = seekTime / 1000

        if (isPlaying) startSeekbarThread()
        setMusicView()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("LifecycleCheck", "onCreate")

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initView()
        if(savedInstanceState == null) loadMusic()
    }

    //UI ?????? ??????
    private fun initView(){
        Log.d("LifecycleCheck", "initView")
        songTitle = findViewById(R.id.songTitle)
        albumImage = findViewById(R.id.albumImage)
        singerName = findViewById(R.id.singerName)
        albumName = findViewById(R.id.albumName)
        songNow = findViewById(R.id.songNow)
        songLength = findViewById(R.id.songLength)
        musicSeekBar = findViewById(R.id.musicSeekBar)
        mainLyrics = findViewById(R.id.mainLyrics)
        nextLyrics = findViewById(R.id.nextLyrics)
        controlButton = findViewById(R.id.controlButton)
        lyricsLayout = findViewById(R.id.lyricsLayout)

        activityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()){

        controlButton.setImageResource(
            if (isPlaying) R.drawable.ic_pause
            else R.drawable.ic_play)

            musicSeekBar.progress = seekTime / 1000
            if (isPlaying) startSeekbarThread()
        }

        controlButton.setOnClickListener {
            controlAudio(false)
            if(!isPlaying) onTrackPlay()
            else onTrackPause()
        }

        lyricsLayout.setOnClickListener{
            val intent = Intent(this, LyricsActivity::class.java)
            activityResultLauncher.launch(intent)
        }
    }

    private fun setMusicView() {
        Log.d("LifecycleCheck", "setMusicView")
        val music = musicList[musicIdx]
        songTitle.text = music.songName
        singerName.text = music.singerName
        albumName.text = music.albumName
        songLength.text = getTimeFormatFromSecs(music.duration)
        songNow.text = getTimeFormatFromSecs(seekTime)
        musicSeekBar.max = music.duration
        mainLyrics.text = music.musicLyrics[0].lyrics
        nextLyrics.text = music.musicLyrics[1].lyrics
        albumImage.setImageBitmap(image)

        musicSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, value: Int, p2: Boolean) {
                seekTime = value * 1000
                songNow.text = getTimeFormatFromSecs(value)
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {
                updateJob?.cancel()
            }

            override fun onStopTrackingTouch(p0: SeekBar?) {
                controlAudio(true)
                setLyricsText()

                if (isPlaying) {
                    startSeekbarThread()
                }
            }
        })
    }

    private fun setLyricsText() {
        val musicLyrics = musicList[musicIdx].musicLyrics
        var isFirst = true
        for (mIdx in musicLyrics.indices.reversed()) {
            val now = musicLyrics[mIdx]
            if (now.startTime >= seekTime) continue
            Log.d("setLyricsText", "" + now.startTime + " " + seekTime)

            isFirst = false
            CoroutineScope(Main).launch {
                mainLyrics.setTextColor(Color.BLUE)
                mainLyrics.text = now.lyrics
                nextLyrics.text =
                    if (mIdx != musicList[musicIdx].musicLyrics.size - 1) musicList[musicIdx].musicLyrics[mIdx + 1].lyrics
                    else ""
            }
            break
        }

        if(isFirst){
            CoroutineScope(Main).launch {
                mainLyrics.setTextColor(Color.GRAY)
                mainLyrics.text = musicLyrics[0].lyrics
                nextLyrics.text = musicLyrics[1].lyrics
            }
        }
    }

    private fun getTimeFormatFromSecs(duration: Int): CharSequence {
        val minutes: Int = duration / 60
        val seconds: Int = duration % 60

        return String.format("%02d:%02d", minutes, seconds)
    }


    //?????? ?????? ?????? ??????
    private fun loadMusic(){
        Log.d("LifecycleCheck", "loadMusic")
        val url: URL? = try {
            URL(urlName)
        } catch (e: MalformedURLException) {
            Log.d("Exception", e.toString())
            null
        }

        val builder: AlertDialog.Builder = AlertDialog.Builder(this)
        builder.setCancelable(false)
        builder.setView(R.layout.dialog_loading)
        val dialog: AlertDialog = builder.create()
        dialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            val jsonResponse = url?.readText()
            val jsonObject = JSONTokener(jsonResponse).nextValue() as JSONObject

            image = Glide
                .with(baseContext)
                .asBitmap()
                .load(jsonObject.getString("image"))
                .apply(RequestOptions().override(200, 200))
                .skipMemoryCache(true)
                .submit()
                .get()
            bitmapList.add(image)

            musicList.add(
                Music(
                    urlName,
                    jsonObject.getString("title"),
                    jsonObject.getString("singer"),
                    jsonObject.getString("album"),
                    getLyricsArray(jsonObject.getString("lyrics")),
                    jsonObject.getInt("duration"),
                    jsonObject.getString("file"))
            )

            withContext(Main){
                setMusicView()
                dialog.dismiss()
            }

        }
    }

    private fun getLyricsArray(lyrics: String): List<MusicLyrics> {
        Log.d("LifecycleCheck", "getLyricsArray")
        val musicLyrics = mutableListOf<MusicLyrics>()
        val musicArr = lyrics.split("\n")
        for (mLine in musicArr) {
            val startTimeStr = mLine.substring(mLine.indexOf("[") + 1, mLine.indexOf("]"))
            val timeArr = startTimeStr.split(":")
            val millisecond = timeArr[0].toLong() * 60 * 1000 +
                    timeArr[1].toLong() * 1000 +
                    timeArr[2].toLong()
            val lyric = mLine.substring(mLine.indexOf("]") + 1)

            musicLyrics.add(MusicLyrics(millisecond, lyric))
        }
        return musicLyrics
    }

    //coroutine ?????? ?????????
    private fun startSeekbarThread() {
        updateJob?.cancel()
        updateJob = updateSeekbar()
    }

    private fun updateSeekbar(): Job {
        return CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                val playerTime = player!!.getCurrentTime()
                if(playerTime - 1000 > seekTime || playerTime + 1000 < seekTime) seekTime += 500
                else seekTime = playerTime

                withContext(Main) {
                    musicSeekBar.progress = seekTime / 1000
                }

                setLyricsText()
                delay(500)
            }
        }
    }

    private fun controlAudio(isSeeking : Boolean){
        if(!serviceBound){//???????????? active ?????? ?????????
            val storage = StorageUtil(applicationContext)
            storage.storeAudio(musicList)
            storage.storeAudioIndex(musicIdx)
            storage.storeImages(bitmapList)
            storage.storePlayingInfo(seekTime)

            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createChannel()
            }

            val playerIntent = Intent(applicationContext, MediaPlayerService::class.java)
            startService(playerIntent)
            bindService(playerIntent,
                serviceConnection,
                Context.BIND_AUTO_CREATE)
        }
        else{//BroadcastReceiver ??????
            Log.d("LifecycleCheck", "broadcastReceiver")

            val storage = StorageUtil(applicationContext)
            storage.storeAudioIndex(musicIdx)
            storage.storePlayingInfo(seekTime)

            val broadcastIntent : Intent =
                if(!isSeeking) {
                    if (!isPlaying){
                        if(seekTime == 0) Intent(Broadcast_PLAY)
                        else Intent(Broadcast_RESUME)
                    }
                    else Intent(Broadcast_PAUSE)
                }
                else{
                    if (isPlaying) Intent(Broadcast_SEEK_TO_PLAY)
                    else Intent(Broadcast_SEEK_TO_PAUSE)
                }
            sendBroadcast(broadcastIntent)
        }
    }

    //service ?????? ??????
    private val serviceConnection = object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as MediaPlayerService.LocalBinder
            player = binder.getService()
            setPlayerListener()
            serviceBound = true
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            Log.d("onService","Service disconnected")
            serviceBound = false
        }
    }

    private fun setPlayerListener() {
        player!!.playPauseListener = { wasPlaying ->
            if (wasPlaying) onTrackPause()
            else onTrackPlay()
            Log.d("lifecycle", "playPauseListener")
        }
        player!!.onCompleteListener = {
            musicSeekBar.progress = 0
            seekTime = 0
            isPlaying = false
            onTrackPause()
            Log.d("lifecycle", "onCompleteListener")
        }
        player!!.onPrepareListener = {
            isPrepared = true
            startSeekbarThread()
        }

        player!!.onSeekCompleteListener = {
            musicSeekBar.progress = player!!.getCurrentTime() / 1000
        }
    }

    private fun createChannel() {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Music Player"
            val descriptionText = "description"
            val importance = NotificationManager.IMPORTANCE_LOW
            val mChannel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(mChannel)
        }
    }

    //??? ??????
    override fun onTrackPlay() {
        isPlaying = true
        controlButton.setImageResource(R.drawable.ic_pause)
        if(isPrepared) startSeekbarThread()
    }

    override fun onTrackPause() {
        Log.d("LifecycleCheck", "onTrackPause")
        isPlaying = false
        controlButton.setImageResource(R.drawable.ic_play)
        updateJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            player!!.stopSelf()
        }
    }
}

/*
- BroadcastReceiver
??????????????? ????????? ???????????? ??? ????????? ???????????? ????????? ?????? ??????
sendBroadcast() ???????????? ?????? intents ????????? system wide call??????.
Broadcast intent??? ??? ???????????? ????????? ????????? ???????????? ???????????????
?????? ????????? ???????????? ?????? ??????????????? ???????????? ?????? ????????? ????????????.
????????? "BroadcastReceiver"??? ????????? ???????????? ??????????????????.
????????? ????????? ???????????? ????????? ????????? ???????????? ????????? ???????????? ???????????? ??????
????????? ?????? ???????????? ???????????? ?????? ?????? ??????????????? ????????????
???????????? intent??? ????????? ??? onReceive ????????? ?????? ?????????

?????? ??????: AndroidManifest.xml ??????, ?????? registerReceiver() ????????? ??????
* */