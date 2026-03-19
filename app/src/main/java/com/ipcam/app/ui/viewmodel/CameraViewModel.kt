package com.ipcam.app.ui.viewmodel

import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import com.google.gson.Gson
import com.ipcam.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.AddIceObserver
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class CameraUiState(
    val status: String = "Connecting...",
    val error: String? = null,
    val isPlaying: Boolean = false,
    val debugLogs: List<String> = emptyList()
)

/**
 * WebRTC JNI is extremely sensitive to threading:
 * - [SdpObserver] callbacks run on libwebrtc's **native signaling thread** (not main).
 * - If we [Handler.post] ICE / SDP work to the **main looper**, [addIceCandidate] runs on
 *   a different thread than [setLocalDescription] / [setRemoteDescription] completions,
 *   which triggers `Check failed: !env->ExceptionCheck()` → SIGABRT in nativeAddIceCandidate.
 *
 * **All** [PeerConnection] calls and WebSocket-driven signaling therefore run on a dedicated
 * [HandlerThread]. Only UI ([SurfaceViewRenderer.init]) and [MutableStateFlow] updates that
 * touch the renderer use the main handler where needed.
 */
class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState

    private val gson = Gson()
    val eglBase: EglBase = EglBase.create()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val signalingThread = HandlerThread("IpCam-WebRTC").apply { start() }
    private val signalingHandler = Handler(signalingThread.looper)

    private var webSocket: WebSocket? = null
    private var peerConnection: PeerConnection? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var videoRenderer: SurfaceViewRenderer? = null
    private var pendingVideoTrack: VideoTrack? = null

    private val localIceQueue = mutableListOf<IceCandidate>()
    private val remoteIceQueue = mutableListOf<RemoteIceCandidate>()
    private val addedRemoteIceKeys = mutableSetOf<String>()
    private val remoteMidToIndex = mutableMapOf<String, Int>()
    private val remoteIndexToMid = mutableMapOf<Int, String>()
    private var remoteMediaLineCount: Int = 0
    private val remoteVideoIndices = mutableSetOf<Int>()
    private val remoteVideoMids = mutableSetOf<String>()

    private data class RemoteIceCandidate(
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int
    )

    private fun jsonNumberToInt(value: Any?, default: Int = 0): Int = when (value) {
        null -> default
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: default
        else -> default
    }

    private fun jsonToSdpMid(value: Any?): String? = when (value) {
        null -> null
        is String -> value.ifBlank { null }
        is Number -> value.toString()
        else -> value.toString().ifBlank { null }
    }

    private fun parseRemoteSdpMetadata(sdp: String) {
        remoteMidToIndex.clear()
        remoteIndexToMid.clear()
        remoteMediaLineCount = 0
        remoteVideoIndices.clear()
        remoteVideoMids.clear()
        var currentMLine = -1
        var currentIsVideo = false
        sdp.lineSequence().forEach { line ->
            when {
                line.startsWith("m=") -> {
                    currentMLine += 1
                    remoteMediaLineCount = currentMLine + 1
                    currentIsVideo = line.startsWith("m=video", ignoreCase = true)
                    if (currentIsVideo) remoteVideoIndices.add(currentMLine)
                }
                line.startsWith("a=mid:") && currentMLine >= 0 -> {
                    val mid = line.removePrefix("a=mid:").trim()
                    if (mid.isNotEmpty()) {
                        remoteMidToIndex[mid] = currentMLine
                        remoteIndexToMid[currentMLine] = mid
                        if (currentIsVideo) remoteVideoMids.add(mid)
                    }
                }
            }
        }
        log("Remote SDP parsed: m-lines=$remoteMediaLineCount mids=$remoteMidToIndex videoIndices=$remoteVideoIndices")
    }

    private data class ParsedCandidate(
        val foundation: String,
        val component: Int,
        val protocol: String,
        val priority: Long,
        val address: String,
        val port: Int,
        val type: String
    )

    private fun parseCandidateLine(candidate: String): ParsedCandidate? {
        val body = candidate.removePrefix("candidate:").trim()
        val tokens = body.split(Regex("\\s+"))
        if (tokens.size < 8) return null
        val foundation = tokens[0]
        val component = tokens[1].toIntOrNull() ?: return null
        val protocol = tokens[2].lowercase(Locale.US)
        val priority = tokens[3].toLongOrNull() ?: return null
        val address = tokens[4]
        val port = tokens[5].toIntOrNull() ?: return null
        val typToken = tokens[6].lowercase(Locale.US)
        val type = tokens[7].lowercase(Locale.US)
        if (typToken != "typ") return null
        if (component <= 0) return null
        if (priority <= 0L) return null
        if (port !in 1..65535) return null
        if (address.isBlank()) return null
        return ParsedCandidate(foundation, component, protocol, priority, address, port, type)
    }

    /**
     * Must run on [signalingHandler] only (same thread as all other PeerConnection API calls).
     */
    private fun safeAddIceCandidate(
        pc: PeerConnection,
        sdpMid: String?,
        sdpMLineIndex: Int,
        sdp: String
    ): Boolean {
        val normalized = sdp.trim().removePrefix("a=").trim()
        val trimmed = normalized
        if (trimmed.isEmpty()) {
            log("ICE: skip empty candidate (end-of-candidates)")
            return true
        }
        if (!trimmed.startsWith("candidate:", ignoreCase = true)) {
            log("ICE: skip invalid sdp (no candidate: prefix): ${trimmed.take(96)}")
            return false
        }
        val parsed = parseCandidateLine(trimmed)
        if (parsed == null) {
            log("ICE: skip malformed candidate line: ${trimmed.take(120)}")
            return false
        }
        // Keep only udp + host/srflx/relay (drop tcp/prflx/unknown lines that have triggered JNI aborts).
        if (parsed.protocol != "udp") {
            log("ICE: skip non-udp candidate protocol=${parsed.protocol}")
            return false
        }
        if (parsed.type != "host" && parsed.type != "srflx" && parsed.type != "relay") {
            log("ICE: skip unsupported candidate type=${parsed.type}")
            return false
        }
        val resolvedMid = sdpMid?.trim()?.ifBlank { null }
        val resolvedIndex = when {
            resolvedMid != null && remoteMidToIndex.containsKey(resolvedMid) ->
                remoteMidToIndex.getValue(resolvedMid)
            else -> sdpMLineIndex
        }
        if (resolvedIndex < 0 || (remoteMediaLineCount > 0 && resolvedIndex >= remoteMediaLineCount)) {
            log("ICE: skip out-of-range mLine index=$resolvedIndex (remote m-lines=$remoteMediaLineCount, mid=$resolvedMid)")
            return false
        }
        val canonicalMid = remoteIndexToMid[resolvedIndex] ?: resolvedMid
        // Viewer is video-only; ignore non-video m-lines to avoid invalid candidate application.
        val isVideoMid = canonicalMid != null && remoteVideoMids.contains(canonicalMid)
        val isVideoIndex = remoteVideoIndices.contains(resolvedIndex)
        if (!isVideoMid && !isVideoIndex) {
            log("ICE: skip non-video candidate mid=${canonicalMid ?: "null"} index=$resolvedIndex")
            return false
        }
        val key = "${trimmed}|$resolvedIndex|${canonicalMid ?: ""}"
        if (!addedRemoteIceKeys.add(key)) {
            return true
        }
        if (pc.signalingState() == PeerConnection.SignalingState.CLOSED) {
            addedRemoteIceKeys.remove(key)
            log("ICE: skip — PeerConnection CLOSED")
            return false
        }
        val ice = IceCandidate(canonicalMid, resolvedIndex, trimmed)
        return try {
            pc.addIceCandidate(ice, object : AddIceObserver {
                override fun onAddSuccess() {
                    log("ICE: add success (mid=${canonicalMid ?: "null"} index=$resolvedIndex type=${parsed.type})")
                }

                override fun onAddFailure(error: String?) {
                    addedRemoteIceKeys.remove(key)
                    log("ICE: add failure (mid=${canonicalMid ?: "null"} index=$resolvedIndex type=${parsed.type}): $error")
                }
            })
            true
        } catch (t: Throwable) {
            Log.e(TAG, "addIceCandidate failed", t)
            log("ICE: addIceCandidate exception: ${t.javaClass.simpleName} ${t.message}")
            addedRemoteIceKeys.remove(key)
            false
        }
    }

    /** Run [block] on the WebRTC signaling thread; safe to call from any thread. */
    private inline fun runOnSignalingThread(crossinline block: () -> Unit) {
        if (Looper.myLooper() == signalingThread.looper) {
            block()
        } else {
            signalingHandler.post { block() }
        }
    }

    init {
        initPeerConnectionFactory()
    }

    // ── Renderer (main thread — EGL / view) ───────────────────────────────────

    fun initVideoRenderer(renderer: SurfaceViewRenderer) {
        renderer.init(eglBase.eglBaseContext, null)
        renderer.setEnableHardwareScaler(true)
        videoRenderer = renderer
        runOnSignalingThread {
            pendingVideoTrack?.let { track ->
                track.addSink(renderer)
                pendingVideoTrack = null
                logUi { _uiState.update { it.copy(status = "Playing", isPlaying = true) } }
            }
        }
    }

    private inline fun logUi(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }

    // ── WebRTC factory (created on VM init thread — OK for this SDK) ───────────

    private fun initPeerConnectionFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(getApplication())
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .createPeerConnectionFactory()
        log("PeerConnectionFactory initialized")
    }

    // ── Connect ───────────────────────────────────────────────────────────────

    fun connect(userId: String, cameraId: String) {
        runOnSignalingThread {
            log("Starting connection — userId=$userId, cameraId=$cameraId")
            val client = OkHttpClient()
            val request = Request.Builder().url(BuildConfig.WS_URL).build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    runOnSignalingThread {
                        log("WebSocket connected")
                        setStatus("WS Connected. Registering...")
                        webSocket.send(
                            gson.toJson(mapOf("type" to "/register-user", "userId" to userId))
                        )
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    runOnSignalingThread { handleWsMessage(text, userId, cameraId) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    runOnSignalingThread {
                        log("WebSocket error: ${t.message}")
                        setError("WebSocket connection error")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    runOnSignalingThread {
                        log("WebSocket closed: $code $reason")
                        setStatus("Disconnected")
                    }
                }
            })
        }
    }

    // ── WebSocket message handler — runs on signaling thread ────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun handleWsMessage(text: String, userId: String, cameraId: String) {
        val message = gson.fromJson(text, Map::class.java) as Map<String, Any?>
        val type = message["type"] as? String
        log("WS message: $type")

        when {
            message["success"] == true && message["message"] == "User registered" -> {
                log("User registered — initializing WebRTC")
                setStatus("Registered. Initializing WebRTC...")
                initWebRTC(userId, cameraId)
                webSocket?.send(
                    gson.toJson(
                        mapOf(
                            "type" to "/request-offer",
                            "userId" to userId,
                            "targetDeviceId" to cameraId
                        )
                    )
                )
            }

            type == "offer" || type == "/offer" -> {
                log("Offer received")
                handleOffer(message, userId, cameraId)
            }

            type == "ice-candidate" || type == "ice-candidate-camera" -> {
                handleRemoteIceCandidate(message)
            }

            message["success"] == false -> {
                val msg = message["message"] as? String ?: "Unknown server error"
                log("Server error: $msg")
                setError(msg)
            }

            else -> {
                log("Unhandled WS message — type=$type keys=${message.keys}")
            }
        }
    }

    // ── WebRTC peer connection init — signaling thread ───────────────────────

    private fun initWebRTC(userId: String, cameraId: String) {
        peerConnection?.close()
        peerConnection = null
        localIceQueue.clear()
        remoteIceQueue.clear()
        addedRemoteIceKeys.clear()
        remoteMidToIndex.clear()
        remoteIndexToMid.clear()
        remoteMediaLineCount = 0
        remoteVideoIndices.clear()
        remoteVideoMids.clear()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:store.easykirana.in:5349")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:store.easykirana.in:5349?transport=udp")
                .setUsername("test")
                .setPassword("test12345678")
                .createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {

                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate ?: return
                    // Native thread → continue on signaling thread (serialized with PC API)
                    signalingHandler.post {
                        if (peerConnection?.remoteDescription != null) {
                            sendLocalIceCandidate(candidate, userId, cameraId)
                        } else {
                            log("Queuing local ICE candidate")
                            localIceQueue.add(candidate)
                        }
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    val track = transceiver?.receiver?.track() as? VideoTrack ?: return
                    signalingHandler.post {
                        log("Video track received via onTrack")
                        val renderer = videoRenderer
                        if (renderer != null) {
                            track.addSink(renderer)
                            pendingVideoTrack = null
                            logUi {
                                _uiState.update {
                                    it.copy(
                                        status = "Video track attached, waiting for ICE...",
                                        isPlaying = true
                                    )
                                }
                            }
                        } else {
                            log("Renderer not ready — queuing video track")
                            pendingVideoTrack = track
                        }
                    }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    logUi {
                        log("Connection state: $newState")
                        when (newState) {
                            PeerConnection.PeerConnectionState.CONNECTED ->
                                _uiState.update { it.copy(status = "Connected — streaming") }
                            PeerConnection.PeerConnectionState.FAILED -> {
                                _uiState.update {
                                    it.copy(status = "Connection: FAILED", isPlaying = false)
                                }
                                setError("Peer connection failed")
                            }
                            PeerConnection.PeerConnectionState.DISCONNECTED ->
                                _uiState.update {
                                    it.copy(status = "Connection: DISCONNECTED", isPlaying = false)
                                }
                            else -> setStatus("Connection: $newState")
                        }
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    logUi {
                        log("ICE state: $state")
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED ->
                                log("ICE connected — media path established")
                            PeerConnection.IceConnectionState.FAILED ->
                                setError("ICE connection failed — check STUN/TURN reachability")
                            PeerConnection.IceConnectionState.DISCONNECTED ->
                                log("ICE disconnected")
                            else -> {}
                        }
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    logUi { log("Signaling state: $state") }
                }

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: org.webrtc.MediaStream?) {}
                override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
                override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(
                    receiver: org.webrtc.RtpReceiver?,
                    streams: Array<out org.webrtc.MediaStream>?
                ) {}
            }
        )

        val transceiver = peerConnection?.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )

        peerConnectionFactory?.getRtpReceiverCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
            ?.let { capabilities ->
                val h264 = capabilities.codecs.filter { codec ->
                    codec.mimeType.lowercase().contains("h264")
                }
                if (h264.isNotEmpty()) {
                    transceiver?.setCodecPreferences(h264)
                    log("H264 codec preference set (${h264.size} variants)")
                } else {
                    log("WARNING: H264 not found in receiver capabilities — using default codec order")
                }
            }

        log("PeerConnection created (recvonly video transceiver)")
    }

    // ── Offer handling — SDP callbacks marshalled to signaling thread ──────────

    @Suppress("UNCHECKED_CAST")
    private fun handleOffer(message: Map<String, Any?>, userId: String, cameraId: String) {
        val offerData = message["offer"] as? Map<String, Any?> ?: return
        val sdp = offerData["sdp"] as? String ?: return
        val typeStr = offerData["type"] as? String ?: "offer"
        parseRemoteSdpMetadata(sdp)

        val remoteDesc = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(typeStr),
            sdp
        )

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                // Native signaling thread → run PC follow-up on our signaling HandlerThread
                signalingHandler.post {
                    val pc = peerConnection ?: return@post
                    log("Remote description set — creating answer")
                    pc.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(answer: SessionDescription?) {
                            answer ?: return
                            signalingHandler.post {
                                val pc2 = peerConnection ?: return@post
                                pc2.setLocalDescription(object : SdpObserver {
                                    override fun onSetSuccess() {
                                        signalingHandler.post {
                                            val pcAns = peerConnection ?: return@post
                                            log("Local description set — send answer + ICE flush")
                                            webSocket?.send(
                                                gson.toJson(
                                                    mapOf(
                                                        "type" to "/answer",
                                                        "deviceId" to cameraId,
                                                        "answer" to mapOf(
                                                            "type" to answer.type.canonicalForm(),
                                                            "sdp" to answer.description
                                                        )
                                                    )
                                                )
                                            )
                                            logUi {
                                                setStatus("Answer sent, exchanging ICE...")
                                            }

                                            localIceQueue.forEach { sendLocalIceCandidate(it, userId, cameraId) }
                                            localIceQueue.clear()

                                            val pending = remoteIceQueue.toList()
                                            remoteIceQueue.clear()
                                            log("Adding ${pending.size} queued remote ICE candidate(s)")
                                            pending.forEach { queued ->
                                                safeAddIceCandidate(
                                                    pcAns,
                                                    queued.sdpMid,
                                                    queued.sdpMLineIndex,
                                                    queued.candidate
                                                )
                                            }
                                        }
                                    }
                                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                                    override fun onCreateFailure(error: String?) {
                                        signalingHandler.post {
                                            log("setLocalDescription create failed: $error")
                                        }
                                    }
                                    override fun onSetFailure(error: String?) {
                                        signalingHandler.post {
                                            log("setLocalDescription set failed: $error")
                                        }
                                    }
                                }, answer)
                            }
                        }

                        override fun onSetSuccess() {}
                        override fun onCreateFailure(error: String?) {
                            signalingHandler.post { log("createAnswer failed: $error") }
                        }
                        override fun onSetFailure(error: String?) {}
                    }, MediaConstraints())
                }
            }

            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                signalingHandler.post {
                    log("setRemoteDescription failed: $error")
                    setError("Failed to handle offer")
                }
            }
        }, remoteDesc)
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleRemoteIceCandidate(message: Map<String, Any?>) {
        val rawCandidate = message["candidate"]
        val data = rawCandidate as? Map<String, Any?>
        if (data == null) {
            log("ERROR: ICE candidate unexpected format — field type=${rawCandidate?.javaClass?.simpleName ?: "null"} keys=${message.keys}")
            return
        }
        val candidateStr = when (val c = data["candidate"]) {
            is String -> c
            else -> {
                log("ERROR: ICE candidate 'candidate' must be string, got ${c?.javaClass?.simpleName}")
                return
            }
        }
        if (candidateStr.isBlank()) {
            log("ICE: ignore blank candidate line")
            return
        }
        val sdpMid = jsonToSdpMid(data["sdpMid"])
        val sdpMLineIndex = jsonNumberToInt(data["sdpMLineIndex"], 0)

        val pc = peerConnection ?: return
        when {
            pc.remoteDescription == null -> {
                log("Queuing remote ICE (no remote SDP yet)")
                remoteIceQueue.add(RemoteIceCandidate(candidateStr, sdpMid, sdpMLineIndex))
            }
            pc.localDescription == null -> {
                log("Queuing remote ICE (waiting for local answer SDP)")
                remoteIceQueue.add(RemoteIceCandidate(candidateStr, sdpMid, sdpMLineIndex))
            }
            else -> {
                if (safeAddIceCandidate(pc, sdpMid, sdpMLineIndex, candidateStr)) {
                    log("Remote ICE candidate added (sdpMid=$sdpMid, index=$sdpMLineIndex)")
                }
            }
        }
    }

    private fun sendLocalIceCandidate(candidate: IceCandidate, userId: String, cameraId: String) {
        webSocket?.send(
            gson.toJson(
                mapOf(
                    "type" to "ice-candidate-user",
                    "userId" to userId,
                    "cameraId" to cameraId,
                    "candidate" to mapOf(
                        "candidate" to candidate.sdp,
                        "sdpMLineIndex" to candidate.sdpMLineIndex,
                        "sdpMid" to candidate.sdpMid
                    )
                )
            )
        )
    }

    fun disconnect() {
        signalingHandler.removeCallbacksAndMessages(null)
        signalingHandler.post {
            webSocket?.close(1000, "User left")
            webSocket = null

            peerConnection?.close()
            peerConnection = null

            localIceQueue.clear()
            remoteIceQueue.clear()
            addedRemoteIceKeys.clear()
            remoteMidToIndex.clear()
            remoteMediaLineCount = 0

            // Release GL view on main — after PC is closed
            mainHandler.post {
                val renderer = videoRenderer
                if (renderer != null) {
                    try {
                        pendingVideoTrack?.removeSink(renderer)
                        renderer.clearImage()
                        renderer.release()
                    } catch (t: Throwable) {
                        Log.w(TAG, "renderer release", t)
                    }
                    videoRenderer = null
                }
                pendingVideoTrack = null
            }

            logUi {
                _uiState.update { CameraUiState() }
            }
            log("Disconnected and cleaned up")
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(debugLogs = emptyList()) }
    }

    override fun onCleared() {
        super.onCleared()
        val done = CountDownLatch(1)
        signalingHandler.post {
            try {
                webSocket?.close(1000, "VM cleared")
                webSocket = null
                peerConnection?.close()
                peerConnection = null
                localIceQueue.clear()
                remoteIceQueue.clear()
                addedRemoteIceKeys.clear()
                remoteMidToIndex.clear()
                remoteIndexToMid.clear()
                remoteMediaLineCount = 0
                remoteVideoIndices.clear()
                remoteVideoMids.clear()
            } catch (_: Throwable) {
            } finally {
                done.countDown()
            }
        }
        try {
            done.await(3, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val mainDone = CountDownLatch(1)
        mainHandler.post {
            try {
                val r = videoRenderer
                if (r != null) pendingVideoTrack?.removeSink(r)
                videoRenderer?.clearImage()
                videoRenderer?.release()
            } catch (_: Throwable) {
            }
            videoRenderer = null
            pendingVideoTrack = null
            mainDone.countDown()
        }
        try {
            mainDone.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        peerConnectionFactory?.dispose()
        eglBase.release()
        signalingThread.quitSafely()
    }

    private fun log(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$ts] $message"
        Log.d(TAG, line)
        _uiState.update { it.copy(debugLogs = (it.debugLogs.takeLast(99) + line)) }
    }

    private fun setStatus(status: String) {
        _uiState.update { it.copy(status = status) }
    }

    private fun setError(error: String) {
        _uiState.update { it.copy(error = error) }
    }

    companion object {
        private const val TAG = "CameraViewModel"
    }
}
