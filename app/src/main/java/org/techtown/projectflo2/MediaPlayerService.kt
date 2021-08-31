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

    //서비스가 시행 중에 pause 신호가 오면 잠시 멈춤
    private val pauseMusic = object: BroadcastReceiver(){
        override fun onReceive(p0: Context, p1: Intent) {
            Log.d("lifecycleCheck", "pause method")
            pauseMedia()
            buildNotification(PlaybackStatus.PAUSE)
        }
    }

    private val seekToPlayMusic = object: BroadcastReceiver(){
        override fun onReceive(p0: Context, p1: Intent) {
            Log.d("lifecycleCheck", "seekToPlayMusic Receiver")

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

    //등록
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

    override fun onBind(p0: Intent): IBinder {
        return iBinder
    }

    //Service Lifecycle에서 activity가 서비스 시작을 요청할 시 call하는 메서드
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d("LifecycleCheck", "service onStartCommand")

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

        //broadcastReceiver 등록
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        callStateListener()//전화 관련 listener 등록
        registerBecomingNoisyReceiver()//receiver 등록
        registerStartAudio()
        registerResumeAudio()
        registerPauseAudio()
        registerSeekToPauseAudio()
        registerSeekToPlayAudio()
    }

    //service 종료 시 호출, mediaPlayer release 필요
    override fun onDestroy() {
        Log.d("LifecycleCheck", "service onDestroy")

        super.onDestroy()
        if(mediaPlayer != null){
            stopMedia()
            mediaPlayer!!.release()
        }
        removeAudioFocus()

        //receiver 및 listener 해제
        if(phoneStateListener != null){
            telephonyManager.listen(phoneStateListener,
            PhoneStateListener.LISTEN_NONE)
        }

        removeNotification()

        unregisterReceiver(becomingNoisyReceiver)
        unregisterReceiver(startMusic)
        unregisterReceiver(resumeMusic)
        unregisterReceiver(pauseMusic)
        unregisterReceiver(seekToPauseMusic)
        unregisterReceiver(seekToPlayMusic)

        //캐시 데이터 삭제
        StorageUtil(applicationContext).clearCachedAudioPlaylist()
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

        mediaPlayer!!.reset()//리셋을 통해 미디어 플레이어가 다른 데이터를 가리키지 않게 설정

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

    private fun playMedia(){//미디어 플레이어 시작

        if(!mediaPlayer!!.isPlaying){
            mediaPlayer!!.seekTo(resumePos)
            mediaPlayer!!.start()
        }
    }

    fun getCurrentTime() : Int{
        return mediaPlayer!!.currentPosition
    }

    private fun stopMedia(){//정지
        if(mediaPlayer == null) return
        if(mediaPlayer!!.isPlaying) mediaPlayer!!.stop()
    }

    private fun pauseMedia(){//일시정지 및 resumePos에 정보 저장장
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

    override fun onCompletion(p0: MediaPlayer?) {
        //노래가 다 끝났을 때 발동
        Log.d("lifecycleCheck", "onCompletion")

        stopMedia()
        stopSelf()
        mediaPlayer!!.seekTo(0)
        buildNotification(PlaybackStatus.PAUSE)
        onCompleteListener?.invoke()
    }

    override fun onPrepared(p0: MediaPlayer?) {
        //updateMetaData()
        //미디어 소스가 플레이할 준비를 마쳤을 때 발동
        buildNotification(PlaybackStatus.PLAYING)
        onPrepareListener?.invoke()
        playMedia()
    }

    override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        //비동기 작업 중 에러가 발생할 시 발동
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
        //seekbar의 활동이 끝날 시 발동
    }

    override fun onInfo(p0: MediaPlayer?, p1: Int, p2: Int): Boolean {
        //어떤 정보를 상호교환시 발동
        return false
    }

    override fun onBufferingUpdate(p0: MediaPlayer?, p1: Int) {
        //네트워크를 통해 스트리밍되는 미디어 소스의 버퍼링 상태를 파악
    }

    override fun onAudioFocusChange(state: Int) {
        //시스템의 오디오 포커스가 업데이트될 때 발동 - 좋은 UX에 필요
        Log.d("onAudioFocusChange", "state: $state")
        when(state){
            AudioManager.AUDIOFOCUS_GAIN //포커스 얻은 상태, 노래 틀기
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
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS // 포커스가 짧은 시간 놓인 상태, 노래 멈춰야 됨
            -> {
                Log.d("onAudioFocusChange", "state: loss")

                if(mediaPlayer!!.isPlaying){
                    mediaPlayer!!.pause()
                    wasPlayed = false
                    playPauseListener?.invoke(true)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK // 포커스가 짧은 시간 놓인 상태, 노래 안 멈춰도 됨
            -> {
                Log.d("onAudioFocusChange", "state: loss transient can duck")

                if(mediaPlayer!!.isPlaying) mediaPlayer!!.setVolume(.1f, .1f)
            }
        }
    }

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

    private fun registerBecomingNoisyReceiver(){
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(becomingNoisyReceiver, intentFilter)
    }

    private fun callStateListener(){
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        //폰 상태 변화를 이제 listener 통해 듣기 가능
        phoneStateListener = object: PhoneStateListener(){
            override fun onCallStateChanged(state: Int, phoneNumber: String) {
                when(state){
                    //전화가 걸리거나 울리고 있다면 mediaPlayer 멈춤
                    TelephonyManager.CALL_STATE_OFFHOOK, TelephonyManager.CALL_STATE_RINGING -> {
                        if(mediaPlayer != null) {
                            pauseMedia()
                            ongoingCall = true
                        }
                    }
                    //전화가 끝날 시 시작
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
        //listener를 manager에 등록해서
        //device call state에 대한 변화를 listen
        telephonyManager.listen(phoneStateListener,
            PhoneStateListener.LISTEN_CALL_STATE)
    }

    // 내부 클래스를 선언해줘야 외부 클래스를 참조하게 된다!
    inner class LocalBinder : Binder(){
        fun getService() : MediaPlayerService{
            Log.d("LifecycleCheck", "service getService")
            return this@MediaPlayerService
        }
    }
}