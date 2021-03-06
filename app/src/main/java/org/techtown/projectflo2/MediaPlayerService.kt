package org.techtown.projectflo2

import android.annotation.SuppressLint
import android.app.Notification.*
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.MediaSessionManager
import android.os.*
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException


class MediaPlayerService : Service(),
    MediaPlayer.OnCompletionListener,
    MediaPlayer.OnPreparedListener,
    MediaPlayer.OnErrorListener,
    MediaPlayer.OnSeekCompleteListener,
    MediaPlayer.OnInfoListener,
    MediaPlayer.OnBufferingUpdateListener,
    AudioManager.OnAudioFocusChangeListener{
    var playPauseListener: ( (Boolean) -> Unit )? = null
    var onSeekCompleteListener: ( () -> Unit )? = null
    var onCompleteListener: ( () -> Unit )? = null
    var onPrepareListener: ( () -> Unit )? = null
    var wasPlayed = false

    companion object{
        const val ACTION_PLAY = "org.techtown.projectflo2.ACTION_PLAY"
        const val ACTION_PAUSE = "org.techtown.projectflo2.ACTION_PAUSE"
        const val ACTION_STOP = "org.techtown.projectflo2.ACTION_STOP"
        const val CHANNEL_ID = "MUSIC_PLAYER"

        const val NOTIFICATION_ID = 101
    }

    private var mediaSessionManager: MediaSessionManager? = null
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var transportControls : MediaControllerCompat.TransportControls
    private lateinit var notificationManager: NotificationManager
    private val iBinder : IBinder = LocalBinder()
    private var mediaPlayer : MediaPlayer? = null
    private lateinit var audioManager : AudioManager
    private var resumePos = 0
    private var ongoingCall = false
    private var phoneStateListener : PhoneStateListener? = null
    private var albumImage : Bitmap? = null
    private lateinit var telephonyManager : TelephonyManager

    private var musicList = arrayListOf<Music>()
    private var mIdx = -1
    private lateinit var activeMusic : Music

    //Service Lifecycle?????? activity??? ????????? ????????? ????????? ??? call?????? ?????????
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        try{
            val storage = StorageUtil(applicationContext)
            musicList = storage.loadAudio()
            mIdx = storage.loadAudioIndex()
            albumImage = storage.loadImage(mIdx)
            resumePos = storage.loadSeekTime()

            if(mIdx != -1 && mIdx < musicList.size)
                activeMusic = musicList[mIdx]
            else stopSelf()
        }catch(e : NullPointerException){ stopSelf() }

        if(!requestAudioFocus()) stopSelf()
        if(mediaSessionManager == null){
            try{
                initMediaPlayer()
                initMediaSession()
            }catch(e:RemoteException){
                e.printStackTrace()
                stopSelf()
            }
        }
        handleIncomingAction(intent)

        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        Log.d("LifecycleCheck", "service onCreate")

        super.onCreate()

        //broadcastReceiver ??????
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        callStateListener()//?????? ?????? listener ??????
        registerReceivers()
    }

    private fun initMediaPlayer(){
        Log.d("LifecycleCheck", "service initMediaPlayer")

        mediaPlayer = MediaPlayer()
        mediaPlayer!!.setOnCompletionListener(this)
        mediaPlayer!!.setOnErrorListener(this)
        mediaPlayer!!.setOnPreparedListener(this)
        mediaPlayer!!.setOnBufferingUpdateListener(this)
        mediaPlayer!!.setOnSeekCompleteListener(this)
        mediaPlayer!!.setOnInfoListener(this)

        mediaPlayer!!.reset()//????????? ?????? ????????? ??????????????? ?????? ???????????? ???????????? ?????? ??????

        mediaPlayer!!.setAudioAttributes(
            AudioAttributes
                .Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        try{
            mediaPlayer!!.setDataSource(activeMusic.fileUrl)
        }catch(e : IOException){
            e.printStackTrace()
            stopSelf()
        }

        mediaPlayer!!.prepareAsync()
    }

    @Throws(RemoteException::class)
    private fun initMediaSession() {
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        mediaSession = MediaSessionCompat(applicationContext, "MusicPlayer")
        transportControls = mediaSession.controller.transportControls

        mediaSession.isActive = true

        updateMetaData()

        mediaSession.setCallback(object: MediaSessionCompat.Callback(){
            override fun onPlay() {
                Log.d("lifecycleCheck", "onPlay callback")
                super.onPlay()
                resumeMedia()
                buildNotification(PlaybackStatus.PLAYING)
            }

            override fun onPause() {
                Log.d("lifecycleCheck", "onPause callback")
                super.onPause()
                pauseMedia()
                buildNotification(PlaybackStatus.PAUSE)
            }

            override fun onStop() {
                Log.d("lifecycleCheck", "onStop callback")
                super.onStop()
                removeNotification()
                stopSelf()
            }

            override fun onSeekTo(pos: Long) {
                super.onSeekTo(pos)
                resumePos = pos.toInt()
                mediaPlayer!!.seekTo(resumePos)
            }
        })
    }

    private fun buildNotification(playbackStatus: PlaybackStatus){
        Log.d("LifecycleCheck", "service buildNotification")

        var notificationAction = R.drawable.ic_pause
        var actionIntent : PendingIntent? = null

        if(playbackStatus == PlaybackStatus.PLAYING){
            notificationAction = R.drawable.ic_pause
            actionIntent = playbackAction(1)
        }else if(playbackStatus == PlaybackStatus.PAUSE){
            notificationAction = R.drawable.ic_play
            actionIntent = playbackAction(0)
        }

        val notificationBuilder : NotificationCompat.Builder =
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) NotificationCompat.Builder(this, CHANNEL_ID)
            else NotificationCompat.Builder(this)

        val notification = notificationBuilder
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(activeMusic.songName)
            .setContentText(activeMusic.singerName)
            .setLargeIcon(albumImage)
            .addAction(notificationAction, "pause", actionIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0)
                .setMediaSession(mediaSession.sessionToken))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
        notification.flags = FLAG_ONGOING_EVENT

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun removeNotification(){
        notificationManager.cancel(NOTIFICATION_ID)
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun playbackAction(actionNumber: Int): PendingIntent? {
        val playbackAction = Intent(this, MediaPlayerService::class.java)
        when (actionNumber) {
            0 -> {
                // Play
                playbackAction.action = ACTION_PLAY
                return PendingIntent.getService(this, actionNumber, playbackAction, 0)
            }
            1 -> {
                // Pause
                playbackAction.action = ACTION_PAUSE
                return PendingIntent.getService(this, actionNumber, playbackAction, 0)
            }
        }
        return null
    }

    private fun handleIncomingAction(playbackAction : Intent?){
        if(playbackAction == null || playbackAction.action == null) return

        val actionString = playbackAction.action
        when {
            actionString.equals(ACTION_PLAY, ignoreCase = true) -> {
                playPauseListener?.invoke(false)
                transportControls.play()
            }
            actionString.equals(ACTION_PAUSE, ignoreCase = true) -> {
                playPauseListener?.invoke(true)
                transportControls.pause()
            }
            actionString.equals(ACTION_STOP, ignoreCase = true) -> {
                playPauseListener?.invoke(true)
                transportControls.stop()
            }
        }
    }

    private fun updateMetaData(){
        //Log.d("updateMetadata", "" + mediaPlayer!!.duration.toLong());
        mediaSession.setMetadata(MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumImage)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, activeMusic.singerName)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, activeMusic.albumName)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, activeMusic.songName)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, activeMusic.duration * 1000L)
            .build())

        mediaSession.setPlaybackState(
            PlaybackStateCompat
                .Builder()
                .setState(
                    PlaybackStateCompat.STATE_PLAYING,
                    mediaPlayer!!.currentPosition.toLong(),
                    1.0f
                )
                .setActions(PlaybackStateCompat.ACTION_SEEK_TO)
                .build()
        )
    }

    private fun playMedia(){//????????? ???????????? ??????
        if(!mediaPlayer!!.isPlaying){
            mediaPlayer!!.seekTo(resumePos)
            mediaPlayer!!.start()
        }
    }

    private fun stopMedia(){//??????
        if(mediaPlayer == null) return
        if(mediaPlayer!!.isPlaying) mediaPlayer!!.stop()
    }

    private fun pauseMedia(){//???????????? ??? resumePos??? ?????? ?????????
        mediaSession.setPlaybackState(
            PlaybackStateCompat
                .Builder()
                .setState(
                    PlaybackStateCompat.STATE_PAUSED,
                    mediaPlayer!!.currentPosition.toLong(),
                    1.0f
                )
                .setActions(PlaybackStateCompat.ACTION_SEEK_TO)
                .build()
        )
        if(mediaPlayer!!.isPlaying){
            mediaPlayer!!.pause()
            resumePos = mediaPlayer!!.currentPosition
        }
    }

    private fun resumeMedia(){
        Log.d("resumeMedia", mediaPlayer.toString())
        mediaPlayer!!.seekTo(resumePos)
        mediaSession.setPlaybackState(
            PlaybackStateCompat
                .Builder()
                .setState(
                    PlaybackStateCompat.STATE_PLAYING,
                    mediaPlayer!!.currentPosition.toLong(),
                    1.0f
                )
                .setActions(PlaybackStateCompat.ACTION_SEEK_TO)
                .build()
        )
        if(!mediaPlayer!!.isPlaying){
            mediaPlayer!!.start()
        }
    }

    fun getCurrentTime() : Int{
        return mediaPlayer!!.currentPosition
    }

    //?????? ?????? ?????? listener
    override fun onCompletion(p0: MediaPlayer?) {
        //????????? ??? ????????? ??? ??????
        Log.d("lifecycleCheck", "onCompletion")

        stopMedia()
        stopSelf()
        mediaPlayer!!.seekTo(0)
        buildNotification(PlaybackStatus.PAUSE)
        onCompleteListener?.invoke()
    }

    override fun onPrepared(p0: MediaPlayer?) {
        //updateMetaData()
        //????????? ????????? ???????????? ????????? ????????? ??? ??????
        buildNotification(PlaybackStatus.PLAYING)
        onPrepareListener?.invoke()
        playMedia()
    }

    override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        //????????? ?????? ??? ????????? ????????? ??? ??????
        when(what){
            MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK
                    -> Log.d("MediaPlayer Error",
                "MEDIA ERROR NOT VALID FOR PROGRESSIVE PLAYBACK $extra")
            MediaPlayer.MEDIA_ERROR_SERVER_DIED
                    -> Log.d("MediaPlayer Error", "MEDIA ERROR SERVER DIED $extra")
            MediaPlayer.MEDIA_ERROR_UNKNOWN
                    -> Log.d("MediaPlayer Error", "MEDIA ERROR UNKNOWN $extra")
        }
        return false
    }

    override fun onSeekComplete(p0: MediaPlayer?) {
        onSeekCompleteListener?.invoke()
        //seekbar??? ????????? ?????? ??? ??????
    }

    override fun onInfo(p0: MediaPlayer?, p1: Int, p2: Int): Boolean {
        //?????? ????????? ??????????????? ??????
        return false
    }

    override fun onBufferingUpdate(p0: MediaPlayer?, p1: Int) {
        //??????????????? ?????? ?????????????????? ????????? ????????? ????????? ????????? ??????
    }

    override fun onAudioFocusChange(state: Int) {
        //???????????? ????????? ???????????? ??????????????? ??? ?????? - ?????? UX??? ??????
        Log.d("onAudioFocusChange", "state: $state")
        when(state){
            AudioManager.AUDIOFOCUS_GAIN //????????? ?????? ??????, ?????? ??????
            -> {
                Log.d("onAudioFocusChange", "state: gain")
                if(mediaPlayer == null) initMediaPlayer()
                else if(!mediaPlayer!!.isPlaying && wasPlayed){
                    playPauseListener?.invoke(false)
                    mediaPlayer!!.start()
                    wasPlayed = false
                }
                mediaPlayer!!.setVolume(1f, 1f)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS // ???????????? ?????? ?????? ?????? ??????, ?????? ????????? ???
            -> {
                Log.d("onAudioFocusChange", "state: loss")

                if(mediaPlayer!!.isPlaying){
                    mediaPlayer!!.pause()
                    wasPlayed = false
                    playPauseListener?.invoke(true)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK // ???????????? ?????? ?????? ?????? ??????, ?????? ??? ????????? ???
            -> {
                Log.d("onAudioFocusChange", "state: loss transient can duck")

                if(mediaPlayer!!.isPlaying) mediaPlayer!!.setVolume(.1f, .1f)
            }
        }
    }

    //Audio Focus ?????? ??????
    private fun requestAudioFocus() : Boolean{
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val result : Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            result = audioManager.requestAudioFocus(
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(this)
                    .build()
            )
        } else {
            result = audioManager.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        if(result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return true
        return false
    }

    private fun removeAudioFocus() : Boolean{
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                == audioManager.abandonAudioFocusRequest(
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    ).setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(this)
                    .build()
            ))
        } else {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED ==
                    audioManager.abandonAudioFocus(this)
        }
    }

    private fun callStateListener(){
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        //??? ?????? ????????? ?????? listener ?????? ?????? ??????
        phoneStateListener = object: PhoneStateListener(){
            override fun onCallStateChanged(state: Int, phoneNumber: String) {
                when(state){
                    //????????? ???????????? ????????? ????????? mediaPlayer ??????
                    TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_RINGING -> {
                        if(mediaPlayer != null) {
                            pauseMedia()
                            ongoingCall = true
                        }
                    }
                    //????????? ?????? ??? ??????
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if(mediaPlayer != null){
                            if(ongoingCall){
                                ongoingCall = false
                                resumeMedia()
                            }
                        }
                    }
                }
            }
        }
        //listener??? manager??? ????????????
        //device call state??? ?????? ????????? listen
        telephonyManager.listen(phoneStateListener,
            PhoneStateListener.LISTEN_CALL_STATE)
    }

    //Receiver ??????
    private fun registerReceivers() {
        registerBecomingNoisyReceiver()//receiver ??????
        registerStartAudio()
        registerResumeAudio()
        registerPauseAudio()
        registerSeekToPauseAudio()
        registerSeekToPlayAudio()
    }

    private fun unregisterReceivers() {
        unregisterReceiver(becomingNoisyReceiver)
        unregisterReceiver(startMusic)
        unregisterReceiver(resumeMusic)
        unregisterReceiver(pauseMusic)
        unregisterReceiver(seekToPauseMusic)
        unregisterReceiver(seekToPlayMusic)
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver(){
        override fun onReceive(p0: Context?, p1: Intent?) {
            Log.d("lifecycleCheck", "becomingNoisyReceiver")
            pauseMedia()
            buildNotification(PlaybackStatus.PAUSE)
        }
    }
    private val startMusic = object: BroadcastReceiver(){
        override fun onReceive(p0: Context, p1: Intent) {
            Log.d("lifecycleCheck", "startMusic method")
            playMedia()
            buildNotification(PlaybackStatus.PLAYING)
        }
    }
    private val resumeMusic = object: BroadcastReceiver(){
        override fun onReceive(p0: Context, p1: Intent) {
            Log.d("lifecycleCheck", "resumeMusic method")
            resumeMedia()
            buildNotification(PlaybackStatus.PLAYING)
        }
    }
    private val pauseMusic = object: BroadcastReceiver(){
        override fun onReceive(p0: Context, p1: Intent) {
            pauseMedia()
            buildNotification(PlaybackStatus.PAUSE)
        }
    }
    private val seekToPlayMusic = object: BroadcastReceiver(){
        override fun onReceive(p0: Context, p1: Intent) {
            val storage = StorageUtil(applicationContext)
            resumePos = storage.loadSeekTime()
            resumeMedia()

            buildNotification(PlaybackStatus.PLAYING)
        }
    }
    private val seekToPauseMusic = object: BroadcastReceiver(){
        override fun onReceive(p0: Context, p1: Intent) {
            val storage = StorageUtil(applicationContext)
            resumePos = storage.loadSeekTime()

            buildNotification(PlaybackStatus.PAUSE)
        }
    }
    private fun registerBecomingNoisyReceiver(){
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(becomingNoisyReceiver, intentFilter)
    }
    private fun registerStartAudio(){
        val filter = IntentFilter(MainActivity.Broadcast_PLAY)
        registerReceiver(startMusic, filter)
    }
    private fun registerResumeAudio(){
        val filter = IntentFilter(MainActivity.Broadcast_RESUME)
        registerReceiver(resumeMusic, filter)
    }
    private fun registerPauseAudio(){
        val filter = IntentFilter(MainActivity.Broadcast_PAUSE)
        registerReceiver(pauseMusic, filter)
    }
    private fun registerSeekToPlayAudio(){
        val filter = IntentFilter(MainActivity.Broadcast_SEEK_TO_PLAY)
        registerReceiver(seekToPlayMusic, filter)
    }
    private fun registerSeekToPauseAudio(){
        val filter = IntentFilter(MainActivity.Broadcast_SEEK_TO_PAUSE)
        registerReceiver(seekToPauseMusic, filter)
    }

    // ?????? ???????????? ??????????????? ?????? ???????????? ???????????? ??????!
    inner class LocalBinder : Binder(){
        fun getService() : MediaPlayerService{
            Log.d("LifecycleCheck", "service getService")
            return this@MediaPlayerService
        }
    }

    override fun onBind(p0: Intent): IBinder {
        return iBinder
    }

    override fun onDestroy() {
        Log.d("LifecycleCheck", "service onDestroy")

        super.onDestroy()
        if(mediaPlayer != null){
            stopMedia()
            mediaPlayer!!.release()
        }
        removeAudioFocus()

        //receiver ??? listener ??????
        if(phoneStateListener != null){
            telephonyManager.listen(phoneStateListener,
                PhoneStateListener.LISTEN_NONE)
        }

        removeNotification()
        unregisterReceivers()

        //?????? ????????? ??????
        StorageUtil(applicationContext).clearCachedAudioPlaylist()
    }
}