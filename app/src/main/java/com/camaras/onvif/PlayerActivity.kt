package com.camaras.onvif

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private var current: String = ""
    private lateinit var status: TextView
    private lateinit var edit: EditText
    private lateinit var playerView: PlayerView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val urls = intent.getStringArrayListExtra("urls") ?: arrayListOf()
        val note = intent.getStringExtra("note") ?: ""

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(8), dp(16), dp(8), dp(8))

        if (note.isNotEmpty()) {
            val n = TextView(this)
            n.text = "ONVIF falló ($note). Probando rutas RTSP comunes."
            n.textSize = 12f
            root.addView(n)
        }

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for ((i, u) in urls.withIndex()) {
            val b = Button(this)
            b.text = if (note.isEmpty()) "Stream ${i + 1}" else "Ruta ${i + 1}"
            b.setOnClickListener { play(u) }
            row.addView(b)
        }
        val scroll = HorizontalScrollView(this)
        scroll.addView(row)
        root.addView(scroll)

        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        edit = EditText(this)
        edit.setSingleLine()
        edit.textSize = 12f
        val go = Button(this)
        go.text = "Ir"
        go.setOnClickListener { play(edit.text.toString().trim()) }
        line.addView(edit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(go)
        root.addView(line)

        playerView = PlayerView(this)
        playerView.useController = false
        root.addView(playerView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(this)
        status.text = ""
        root.addView(status)

        setContentView(root)
        current = urls.firstOrNull() ?: ""
    }

    override fun onStart() {
        super.onStart()
        if (current.isNotEmpty()) play(current)
    }

    override fun onStop() {
        super.onStop()
        stop()
    }

    private fun stop() {
        playerView.player = null
        player?.release()
        player = null
    }

    private fun play(url: String) {
        if (url.isEmpty()) return
        stop()
        current = url
        edit.setText(url)
        status.text = "Cargando…"
        val p = ExoPlayer.Builder(this).build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) status.text = "Cargando…"
                if (state == Player.STATE_READY) status.text = "En vivo"
                if (state == Player.STATE_ENDED) status.text = "Terminó"
            }

            override fun onPlayerError(error: PlaybackException) {
                status.text = "Error: ${error.errorCodeName}. Prueba otro stream o URL."
            }
        })
        val src = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(src)
        p.prepare()
        p.playWhenReady = true
        playerView.player = p
        player = p
    }
}
