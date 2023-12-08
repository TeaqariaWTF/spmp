package com.toasterofbread.spmp.platform.playerservice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.toasterofbread.composekit.platform.PlatformPreferences
import com.toasterofbread.composekit.platform.PlatformPreferencesListener
import com.toasterofbread.composekit.utils.common.launchSingle
import com.toasterofbread.spmp.model.mediaitem.song.Song
import com.toasterofbread.spmp.model.mediaitem.song.SongData
import com.toasterofbread.spmp.model.mediaitem.song.SongRef
import com.toasterofbread.spmp.model.settings.category.DesktopSettings
import com.toasterofbread.spmp.platform.PlatformServiceImpl
import com.toasterofbread.spmp.platform.PlayerListener
import com.toasterofbread.spmp.platform.getUiLanguage
import com.toasterofbread.spmp.resources.getString
import com.toasterofbread.spmp.youtubeapi.radio.RadioInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ
import org.zeromq.ZMsg
import java.net.InetAddress

private const val POLL_STATE_INTERVAL: Long = 100
private const val POLL_TIMEOUT_MS: Long = 10000

abstract class ZmqSpMsPlayerService: PlatformServiceImpl(), PlayerService {
    abstract val listeners: List<PlayerListener>

    var socket_load_state: PlayerServiceLoadState by mutableStateOf(PlayerServiceLoadState(true))
        private set

    private fun getServerPort(): Int = DesktopSettings.Key.SERVER_PORT.get(context)
    private fun getServerIp(): String = DesktopSettings.Key.SERVER_IP_ADDRESS.get(context)

    private fun getClientName(): String {
        val host: String = InetAddress.getLocalHost().hostName
        val os: String = System.getProperty("os.name")

        return getString("app_name") + " [$os, $host]"
    }

    private val prefs_listener: PlatformPreferencesListener = object : PlatformPreferencesListener {
        override fun onChanged(prefs: PlatformPreferences, key: String) {
            when (key) {
                DesktopSettings.Key.SERVER_IP_ADDRESS.getName(),
                DesktopSettings.Key.SERVER_PORT.getName() -> {
                    restart_connection = true
                    cancel_connection = true
                }
            }
        }
    }

    private val zmq: ZContext = ZContext()
    private lateinit var socket: ZMQ.Socket
    private val json: Json = Json { ignoreUnknownKeys = true }
    private val queued_messages: MutableList<Pair<String, List<Any>>> = mutableListOf()
    private val poll_coroutine_scope: CoroutineScope = CoroutineScope(Job())
    private val connect_coroutine_scope: CoroutineScope = CoroutineScope(Job())
    private var cancel_connection: Boolean = false
    private var restart_connection: Boolean = false

    internal var playlist: MutableList<Song> = mutableListOf()
        private set

    internal var _state: MediaPlayerState = MediaPlayerState.IDLE
    internal var _is_playing: Boolean = false
    internal var _current_song_index: Int = -1
    internal var _duration_ms: Long = -1
    internal var _radio_state: RadioInstance.RadioState = RadioInstance.RadioState()
    internal var _repeat_mode: MediaPlayerRepeatMode = MediaPlayerRepeatMode.NONE
    internal var _volume: Float = 1f
    internal var current_song_time: Long = -1

    protected fun sendRequest(action: String, vararg params: Any) {
        synchronized(queued_messages) {
            queued_messages.add(Pair(action, params.asList()))
        }
    }

    internal fun updateIsPlaying(playing: Boolean) {
        if (playing == _is_playing) {
            return
        }

        val position_ms = current_position_ms
        _is_playing = playing
        updateCurrentSongPosition(position_ms)
    }

    internal fun updateCurrentSongPosition(position_ms: Long) {
        require(position_ms >= 0) { position_ms }
        if (_is_playing) {
            current_song_time = System.currentTimeMillis() - position_ms
        }
        else {
            current_song_time = position_ms
        }
    }

    private fun getServerURL(): String =
        "tcp://${getServerIp()}:${getServerPort()}"

    override fun onCreate() {
        context.getPrefs().addListener(prefs_listener)

        socket = zmq.createSocket(SocketType.DEALER)
        socket.connectToServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        poll_coroutine_scope.cancel()
        connect_coroutine_scope.cancel()
        context.getPrefs().removeListener(prefs_listener)
    }

    private fun onSocketConnectionLost(expired_timeout_ms: Long) {
        println("Connection to server timed out after ${expired_timeout_ms}ms, reconnecting...")
        socket.connectToServer()
    }

    private fun ZMQ.Socket.connectToServer() {
        connect_coroutine_scope.launch {
            do {
                cancel_connection = false
                restart_connection = false

                val server_url: String = getServerURL()
                val handshake: SpMsClientHandshake = SpMsClientHandshake(getClientName(), SpMsClientType.HEADLESS_PLAYER, context.getUiLanguage())

                if (
                    !tryConnectToServer(
                        socket = this@connectToServer,
                        server_url = server_url,
                        handshake = handshake,
                        json = json,
                        shouldCancelConnection = { cancel_connection },
                        setLoadState = { socket_load_state = it }
                    )
                ) {
                    disconnect(server_url)
                    continue
                }

                poll_coroutine_scope.launchSingle(Dispatchers.IO) {
                    val context: ZMQ.Context = ZMQ.context(1)
                    val poller: ZMQ.Poller = context.poller()
                    poller.register(socket, ZMQ.Poller.POLLIN)

                    while (true) {
                        delay(POLL_STATE_INTERVAL)
                        if (!pollServerState(poller, POLL_TIMEOUT_MS)) {
                            onSocketConnectionLost(POLL_TIMEOUT_MS)
                            break
                        }
                    }
                }
            }
            while (restart_connection)
        }
    }

    private fun ZMQ.Socket.pollServerState(poller: ZMQ.Poller, timeout: Long = -1): Boolean {
        val events: ZMsg
        if (poller.poll(timeout) > 0) {
            events = ZMsg.recvMsg(this)
        }
        else {
            return false
        }

        for (i in 0 until events.size) {
            val event_str: String = events.pop().data.decodeToString().removeSuffix("\u0000")
            if (event_str.isEmpty()) {
                continue
            }

            val event: SpMsPlayerEvent
            try {
                event = json.decodeFromString(event_str) ?: continue
            }
            catch (e: Throwable) {
                throw RuntimeException("Parsing event failed '$event_str'", e)
            }

            try {
                applyPlayerEvent(event)
            }
            catch (e: Throwable) {
                throw RuntimeException("Processing event $event failed", e)
            }
        }

        val reply: ZMsg = ZMsg()
        synchronized(queued_messages) {
            if (queued_messages.isEmpty()) {
                reply.add(byteArrayOf())
            }
            else {
                for (message in queued_messages) {
                    reply.add(message.first)
                    reply.add(Gson().toJson(message.second))
                }
            }
            queued_messages.clear()

            return reply.send(this@pollServerState)
        }
    }
}