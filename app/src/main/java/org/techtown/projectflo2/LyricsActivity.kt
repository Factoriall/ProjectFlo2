package org.techtown.projectflo2

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.techtown.projectflo2.MainActivity.Companion.bitmapList
import org.techtown.projectflo2.MainActivity.Companion.isPlaying
import org.techtown.projectflo2.MainActivity.Companion.musicIdx
import org.techtown.projectflo2.MainActivity.Companion.musicList
import org.techtown.projectflo2.MainActivity.Companion.player
import org.techtown.projectflo2.MainActivity.Companion.seekTime
import org.techtown.projectflo2.MainActivity.Companion.serviceBound

class LyricsActivity : AppCompatActivity(), PlayerControl{
    private lateinit var music : Music
    private var isToggled : Boolean = false
    var updateJob : Job? = null

    private lateinit var songTitle : TextView
    private lateinit var singerName : TextView
    private lateinit var lyricsLayout : LinearLayout
    private lateinit var toggleButton : ImageView
    private lateinit var musicSeekBar: SeekBar
    private lateinit var controlButton : ImageView
    private lateinit var exitButton : ImageView
    private lateinit var notificationManager : NotificationManager

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isToggled", isToggled)
        updateJob?.cancel()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        isToggled = savedInstanceState.getBoolean("isToggled")
        toggleButton.setImageResource(
            if(isToggled) R.drawable.ic_toggle_clicked
            else R.drawable.ic_toggle)
        super.onRestoreInstanceState(savedInstanceState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lyrics)
        music = musicList[musicIdx]
        if(player != null) setPlayerListener()

        initView()
        if(isPlaying) startSeekbarThread()
    }

    private fun initView() {
        songTitle = findViewById(R.id.songTitle)
        songTitle.text = music.songName
        singerName = findViewById(R.id.singerName)
        singerName.text = music.singerName
        lyricsLayout = findViewById(R.id.lyricsLayout)

        musicSeekBar = findViewById(R.id.musicSeekBar)
        musicSeekBar.max = music.duration
        musicSeekBar.progress = seekTime / 1000
        musicSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, value: Int, p2: Boolean) {
                seekTime = value * 1000
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

        controlButton = findViewById(R.id.controlButton)
        controlButton.setImageResource(
            if(isPlaying) R.drawable.ic_pause
            else R.drawable.ic_play)
        controlButton.setOnClickListener{
            controlAudio(false)
            if(isPlaying) onTrackPause()
            else onTrackPlay()
        }

        exitButton = findViewById(R.id.exitButton)
        exitButton.setOnClickListener {
            updateJob?.cancel()
            finish()
        }

        toggleButton = findViewById(R.id.toggleButton)
        toggleButton.setOnClickListener {
            toggleButton.setImageResource(
                if(!isToggled) R.drawable.ic_toggle_clicked
                else R.drawable.ic_toggle)
            isToggled = !isToggled
        }

        for((idx, lyric) in music.musicLyrics.withIndex()){
            val lyricsText = TextView(this)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(20, 20, 20, 20)
            lyricsText.layoutParams = params
            lyricsText.textSize = 20F
            lyricsText.setTextColor(Color.GRAY)
            lyricsText.text = lyric.lyrics
            lyricsText.tag = idx
            lyricsText.setOnClickListener {
                if(isToggled){
                    val move = (music.musicLyrics[idx].startTime).toInt()
                    musicSeekBar.progress = move / 1000 + 1
                    controlAudio(true)
                    setLyricsTextView(idx)
                }
                else{
                    updateJob?.cancel()
                    finish()
                }
            }
            lyricsLayout.addView(lyricsText)
        }
        musicSeekBar.max = music.duration
    }

    private fun setLyricsText() {
        for(mIdx in music.musicLyrics.indices.reversed()){
            val now = music.musicLyrics[mIdx]
            if(now.startTime > seekTime) continue

            setLyricsTextView(mIdx)
            break
        }
    }

    private fun setLyricsTextView(mIdx: Int) {
        CoroutineScope(Dispatchers.Main).launch {
            for(i in 0..lyricsLayout.childCount){
                val view = lyricsLayout.getChildAt(i)
                if (view is TextView) {
                    if (view.tag == mIdx) view.setTextColor(Color.BLUE)
                    else view.setTextColor(Color.GRAY)
                }
            }
        }
    }

    //Coroutine 관련 작업
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

                withContext(Dispatchers.Main) {
                    musicSeekBar.progress = seekTime / 1000
                }

                setLyricsText()
                delay(500)
            }
        }
    }

    //Service 관련 작업들
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

    private fun controlAudio(isSeeking : Boolean){
        if(!serviceBound){//서비스가 active 하지 않다면
            Log.d("LifecycleCheck", "startService")
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
            bindService(playerIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        else{//BroadcastReceiver 통해
            Log.d("LifecycleCheck", "broadcastReceiver")

            val storage = StorageUtil(applicationContext)
            storage.storeAudioIndex(musicIdx)
            storage.storePlayingInfo(seekTime)

            val broadcastIntent : Intent =
                if(!isSeeking) {
                    if (!isPlaying){
                        if(seekTime == 0) Intent(MainActivity.Broadcast_PLAY)
                        else Intent(MainActivity.Broadcast_RESUME)
                    }
                    else Intent(MainActivity.Broadcast_PAUSE)
                }
                else{
                    Log.d("broadcastIntent", "touch seekbar")
                    if (isPlaying) Intent(MainActivity.Broadcast_SEEK_TO_PLAY)
                    else Intent(MainActivity.Broadcast_SEEK_TO_PAUSE)
                }
            sendBroadcast(broadcastIntent)
        }
    }

    private fun createChannel() {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Music Player"
            val descriptionText = "description"
            val importance = NotificationManager.IMPORTANCE_LOW
            val mChannel = NotificationChannel(MediaPlayerService.CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(mChannel)
        }
    }

    private fun setPlayerListener() {
        player!!.playPauseListener = { wasPlaying ->
            Log.d("playPauseListener", "true")
            if (wasPlaying) onTrackPause()
            else onTrackPlay()
        }
        player!!.onCompleteListener = {
            musicSeekBar.progress = 0
            seekTime = 0
            isPlaying = false
            onTrackPause()
        }
        player!!.onPrepareListener = {
            MainActivity.isPrepared = true
            startSeekbarThread()
        }

        player!!.onSeekCompleteListener = {
            musicSeekBar.progress = player!!.getCurrentTime() / 1000
        }
    }

    //PlayerControl interface
    override fun onTrackPlay() {
        isPlaying = true
        controlButton.setImageResource(R.drawable.ic_pause)
        if(MainActivity.isPrepared) startSeekbarThread()
    }

    override fun onTrackPause() {
        isPlaying = false
        controlButton.setImageResource(R.drawable.ic_play)
        updateJob?.cancel()
    }
}