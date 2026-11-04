package com.camaras.onvif

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private val chips = ArrayList<Pair<Button, String>>()

    private fun dp(v: Int): Int = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Ui.DARK

        val urls = intent.getStringArrayListExtra("urls") ?: arrayListOf()
        val note = intent.getStringExtra("note") ?: ""
        val fromSaved = intent.getBooleanExtra("fromSaved", false)
        val via = intent.getStringExtra("via") ?: ""

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.DARK)
        root.setPadding(dp(14), dp(14), dp(14), dp(12))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val back = Ui.button(this, "‹  Volver", Ui.DARK2, Color.WHITE)
        back.setOnClickListener { finish() }
        top.addView(back)
        if (!fromSaved) {
            val save = Ui.button(this, "💾  Guardar", Ui.BLUE, Color.WHITE)
            save.setOnClickListener { saveDialog() }
            val sl = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            sl.leftMargin = dp(8)
            top.addView(save, sl)
        }
        if (via.isNotEmpty()) {
            val v = Ui.label(this, via, 13f, Ui.SOFT)
            v.setPadding(dp(12), 0, 0, 0)
            top.addView(v)
        }
        root.addView(top)

        if (note.isNotEmpty()) {
            val n = Ui.label(this, "ONVIF falló ($note). Probando rutas RTSP comunes.", 12f, Color.parseColor("#FFB74D"))
            n.setPadding(dp(4), dp(10), dp(4), 0)
            root.addView(n)
        }

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for ((i, u) in urls.withIndex()) {
            val b = Ui.button(this, if (note.isEmpty()) "Stream ${i + 1}" else "Ruta ${i + 1}", Ui.DARK2, Color.WHITE)
            b.textSize = 13f
            b.setPadding(dp(16), dp(8), dp(16), dp(8))
            b.setOnClickListener { play(u) }
            chips.add(Pair(b, u))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(8)
            row.addView(b, lp)
        }
        if (urls.size > 1) {
            val scroll = HorizontalScrollView(this)
            scroll.isHorizontalScrollBarEnabled = false
            scroll.addView(row)
            root.addView(scroll, Ui.lp(this, 12))
        }

        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        edit = EditText(this)
        edit.setSingleLine()
        edit.textSize = 12f
        edit.setTextColor(Color.WHITE)
        edit.setHintTextColor(Ui.SOFT)
        edit.hint = "rtsp://..."
        edit.setPadding(dp(16), dp(10), dp(16), dp(10))
        edit.background = Ui.round(this, Ui.DARK2, 16)
        val go = Ui.button(this, "Ir", Ui.BLUE, Color.WHITE)
        go.setOnClickListener { play(edit.text.toString().trim()) }
        line.addView(edit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val glp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        glp.leftMargin = dp(8)
        line.addView(go, glp)
        root.addView(line, Ui.lp(this, 10))

        playerView = PlayerView(this)
        playerView.useController = false
        val plp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        plp.topMargin = dp(12)
        root.addView(playerView, plp)

        status = Ui.label(this, "", 13f, Ui.SOFT)
        status.gravity = Gravity.CENTER
        status.setPadding(0, dp(10), 0, 0)
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

    private fun saveDialog() {
        val url = current
        if (url.isEmpty()) return
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(22), dp(22), dp(16))

        val n = Ui.field(this, "Nombre", Store.host(url), false)
        val r = Ui.field(this, "Dirección remota (opcional)", "", false)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END
        val cancel = Ui.button(this, "Cancelar", Color.TRANSPARENT, Ui.MUTED)
        val ok = Ui.button(this, "Guardar", Ui.BLUE, Color.WHITE)
        row.addView(cancel)
        row.addView(ok)

        box.addView(Ui.label(this, "Guardar cámara", 18f, Ui.TEXT, true))
        box.addView(n, Ui.lp(this, 14))
        box.addView(r, Ui.lp(this, 10))
        box.addView(
            Ui.label(
                this,
                "Para verla desde otra red: abre un puerto en tu router y usa tu IP pública o DDNS (ej: casa.ddns.net:8554), o usa una VPN como Tailscale y deja esto vacío. Mira el README.",
                12f, Ui.MUTED
            ),
            Ui.lp(this, 10)
        )
        box.addView(row, Ui.lp(this, 14))

        val d = AlertDialog.Builder(this).setView(box).create()
        cancel.setOnClickListener { d.dismiss() }
        ok.setOnClickListener {
            val name = n.text.toString().trim().ifEmpty { Store.host(url) }
            Store.upsert(this, SavedCam(name, url, Store.cleanRemote(r.text.toString())))
            Toast.makeText(this, "Guardada", Toast.LENGTH_SHORT).show()
            d.dismiss()
        }
        Ui.show(this, d)
    }

    private fun highlight() {
        for ((b, u) in chips) {
            b.background = Ui.round(this, if (u == current) Ui.BLUE else Ui.DARK2, 20)
        }
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
        highlight()
        status.text = "Cargando…"
        val p = ExoPlayer.Builder(this).build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_BUFFERING) status.text = "Cargando…"
                if (state == Player.STATE_READY) status.text = "● En vivo"
                if (state == Player.STATE_ENDED) status.text = "Terminó"
            }

            override fun onPlayerError(error: PlaybackException) {
                status.text = "Error: ${error.errorCodeName}. Revisa la red o prueba otra URL."
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
