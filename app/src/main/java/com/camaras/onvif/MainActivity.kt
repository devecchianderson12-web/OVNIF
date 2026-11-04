package com.camaras.onvif

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class MainActivity : Activity() {

    private val cams = LinkedHashMap<String, Camera>()
    private val shown = ArrayList<Camera>()
    private val savedList = ArrayList<SavedCam>()
    private lateinit var adapter: CamAdapter
    private lateinit var savedAdapter: SavedAdapter
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var btn: Button
    private lateinit var tabSaved: Button
    private lateinit var tabScan: Button
    private var mode = 0
    @Volatile private var scanning = false

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private inner class CamAdapter : ArrayAdapter<Camera>(this@MainActivity, 0, this@MainActivity.shown) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val c = getItem(position)!!
            val sub = listOf(c.name, if (c.ports.isEmpty()) "" else "puertos " + c.ports.joinToString(", "))
                .filter { it.isNotBlank() }.joinToString(" · ")
            val badge = if (c.onvif) Pair("ONVIF", true) else Pair("Posible cámara", false)
            return makeCard("📷", c.ip, sub, listOf(badge))
        }
    }

    private inner class SavedAdapter : ArrayAdapter<SavedCam>(this@MainActivity, 0, this@MainActivity.savedList) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val c = getItem(position)!!
            val badges = ArrayList<Pair<String, Boolean>>()
            badges.add(Pair("🏠 Local", true))
            if (c.remote.isNotBlank()) badges.add(Pair("🌐 Remoto", true)) else badges.add(Pair("Sin acceso remoto", false))
            return makeCard("📹", c.name, Store.host(c.url), badges)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BLUE

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.setPadding(dp(24), dp(28), dp(24), dp(22))
        header.background = Ui.bottomRound(this, Ui.BLUE, 28)
        header.addView(Ui.label(this, "ONVNIF", 30f, Color.WHITE, true))
        header.addView(Ui.label(this, "Tus cámaras, en casa o desde cualquier red", 14f, Color.parseColor("#BBDEFB")))

        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabSaved = Ui.button(this, "📹  Mis cámaras", Color.WHITE, Ui.BLUE)
        tabScan = Ui.button(this, "🔍  Buscar en red", Color.WHITE, Ui.BLUE)
        tabSaved.setOnClickListener { setMode(0) }
        tabScan.setOnClickListener { setMode(1) }
        val t1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        t1.rightMargin = dp(6)
        val t2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        t2.leftMargin = dp(6)
        tabs.addView(tabSaved, t1)
        tabs.addView(tabScan, t2)
        header.addView(tabs, Ui.lp(this, 18))

        btn = Ui.button(this, "🔍  Buscar cámaras", Ui.BLUE, Color.WHITE)
        btn.setOnClickListener { scan() }
        val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        blp.setMargins(dp(16), dp(14), dp(16), 0)

        status = Ui.label(this, "", 14f, Ui.MUTED)
        status.setPadding(dp(22), dp(14), dp(22), dp(8))

        list = ListView(this)
        adapter = CamAdapter()
        savedAdapter = SavedAdapter()
        list.divider = ColorDrawable(Color.TRANSPARENT)
        list.dividerHeight = dp(10)
        list.selector = ColorDrawable(Color.TRANSPARENT)
        list.setPadding(dp(16), dp(4), dp(16), dp(16))
        list.clipToPadding = false
        list.setOnItemClickListener { _, _, pos, _ ->
            if (mode == 0) {
                if (pos < savedList.size) openSaved(savedList[pos])
            } else {
                if (pos < shown.size) ask(shown[pos])
            }
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            if (mode == 0 && pos < savedList.size) {
                manage(savedList[pos])
            }
            true
        }

        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(btn, blp)
        root.addView(status)
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        setMode(if (Store.load(this).isEmpty()) 1 else 0)
    }

    override fun onResume() {
        super.onResume()
        refreshSaved()
    }

    private fun styleTab(b: Button, selected: Boolean) {
        b.background = Ui.round(this, if (selected) Color.WHITE else Color.parseColor("#33FFFFFF"), 20)
        b.setTextColor(if (selected) Ui.BLUE else Color.WHITE)
    }

    private fun setMode(m: Int) {
        mode = m
        styleTab(tabSaved, m == 0)
        styleTab(tabScan, m == 1)
        if (m == 0) {
            list.adapter = savedAdapter
            btn.visibility = View.GONE
            refreshSaved()
        } else {
            list.adapter = adapter
            btn.visibility = View.VISIBLE
            status.text = if (shown.isEmpty())
                "Conéctate al WiFi de tu casa y toca Buscar."
            else
                "${shown.size} encontradas. Toca una para ver."
        }
    }

    private fun refreshSaved() {
        savedList.clear()
        savedList.addAll(Store.load(this))
        savedAdapter.notifyDataSetChanged()
        if (mode == 0) {
            status.text = if (savedList.isEmpty())
                "Aún no hay cámaras guardadas. Ve a «Buscar en red», abre una y toca Guardar."
            else
                "${savedList.size} guardadas. Toca una para ver · mantén pulsado para editar."
        }
    }

    private fun makeCard(icon: String, title: String, subtitle: String, badges: List<Pair<String, Boolean>>): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(14), dp(14), dp(14), dp(14))
        card.background = Ui.round(this, Color.WHITE, 18, Ui.BORDER, 1)

        val iconBox = TextView(this)
        iconBox.text = icon
        iconBox.textSize = 22f
        iconBox.gravity = Gravity.CENTER
        iconBox.background = Ui.round(this, Ui.BLUE_LIGHT, 14)
        card.addView(iconBox, LinearLayout.LayoutParams(dp(48), dp(48)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), 0, dp(8), 0)
        col.addView(Ui.label(this, title, 17f, Ui.TEXT, true))
        if (subtitle.isNotBlank()) col.addView(Ui.label(this, subtitle, 13f, Ui.MUTED))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        for (b in badges) {
            val badge = Ui.label(this, b.first, 12f, if (b.second) Ui.GREEN else Ui.MUTED)
            badge.setPadding(dp(10), dp(3), dp(10), dp(3))
            badge.background = Ui.round(this, if (b.second) Ui.GREEN_LIGHT else Ui.GRAY_LIGHT, 12)
            val bl = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            bl.rightMargin = dp(6)
            row.addView(badge, bl)
        }
        col.addView(row, Ui.lp(this, 6))

        card.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(Ui.label(this, "›", 28f, Ui.SOFT))
        return card
    }

    // ---------- Busqueda en red ----------

    private fun bindWifi() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            for (n in cm.allNetworks) {
                val c = cm.getNetworkCapabilities(n)
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    cm.bindProcessToNetwork(n)
                    break
                }
            }
        } catch (e: Exception) {
            // ignorar
        }
    }

    private fun unbindNet() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.bindProcessToNetwork(null)
        } catch (e: Exception) {
            // ignorar
        }
    }

    private fun setScanning(on: Boolean) {
        scanning = on
        btn.isEnabled = !on
        btn.alpha = if (on) 0.6f else 1f
        btn.text = if (on) "Buscando…" else "🔍  Buscar cámaras"
    }

    private fun scan() {
        if (scanning) return
        synchronized(cams) { cams.clear() }
        shown.clear()
        adapter.notifyDataSetChanged()
        setScanning(true)
        status.text = "Buscando cámaras en tu red…"
        bindWifi()
        Thread {
            val t1 = Thread { Discovery.wsDiscovery(this, 5000, cams) { refresh(false) } }
            val t2 = Thread { Discovery.portScan(cams) { refresh(false) } }
            t1.start()
            t2.start()
            t1.join()
            t2.join()
            unbindNet()
            refresh(true)
        }.start()
    }

    private fun refresh(done: Boolean) {
        val snap = synchronized(cams) {
            cams.values.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
        }
        runOnUiThread {
            shown.clear()
            shown.addAll(snap)
            adapter.notifyDataSetChanged()
            if (done) setScanning(false)
            if (mode == 1) {
                if (done) {
                    status.text = if (shown.isEmpty())
                        "No se encontraron cámaras. Verifica que estés en el WiFi."
                    else
                        "${shown.size} encontradas. Toca una para ver."
                } else {
                    status.text = "Buscando… ${shown.size} encontradas"
                }
            }
        }
    }

    private fun ask(cam: Camera) {
        val prefs = getSharedPreferences("cams", MODE_PRIVATE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(22), dp(22), dp(16))

        val u = Ui.field(this, "Usuario", prefs.getString("user", "admin") ?: "admin", false)
        val p = Ui.field(this, "Contraseña", prefs.getString("pass", "") ?: "", true)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END
        val cancel = Ui.button(this, "Cancelar", Color.TRANSPARENT, Ui.MUTED)
        val ok = Ui.button(this, "Ver cámara", Ui.BLUE, Color.WHITE)
        row.addView(cancel)
        row.addView(ok)

        box.addView(Ui.label(this, "Conectar a ${cam.ip}", 18f, Ui.TEXT, true))
        box.addView(Ui.label(this, "Usuario y contraseña de la cámara", 13f, Ui.MUTED))
        box.addView(u, Ui.lp(this, 16))
        box.addView(p, Ui.lp(this, 10))
        box.addView(row, Ui.lp(this, 18))

        val dialog = AlertDialog.Builder(this).setView(box).create()
        cancel.setOnClickListener { dialog.dismiss() }
        ok.setOnClickListener {
            val user = u.text.toString().trim()
            val pass = p.text.toString()
            prefs.edit().putString("user", user).putString("pass", pass).apply()
            dialog.dismiss()
            open(cam, user, pass)
        }
        Ui.show(this, dialog)
    }

    private fun open(cam: Camera, user: String, pass: String) {
        status.text = "Conectando a ${cam.ip}…"
        Thread {
            var note = ""
            val urls: List<String> = try {
                Onvif.streams(cam, user, pass)
            } catch (e: Exception) {
                note = e.message ?: "Error ONVIF"
                Onvif.fallback(cam.ip, user, pass)
            }
            val finalNote = note
            runOnUiThread {
                status.text = "${shown.size} encontradas. Toca una para ver."
                val i = Intent(this, PlayerActivity::class.java)
                i.putStringArrayListExtra("urls", ArrayList(urls))
                i.putExtra("note", finalNote)
                startActivity(i)
            }
        }.start()
    }

    // ---------- Camaras guardadas ----------

    private fun openSaved(cam: SavedCam) {
        status.text = "Conectando a ${cam.name}…"
        Thread {
            val r = Store.pick(cam)
            runOnUiThread {
                refreshSaved()
                val i = Intent(this, PlayerActivity::class.java)
                i.putStringArrayListExtra("urls", arrayListOf(r.first))
                i.putExtra("note", "")
                i.putExtra("fromSaved", true)
                i.putExtra("via", if (r.second) "🌐 Red remota" else "🏠 Red local")
                i.putExtra("alt", Store.alternate(cam, r.second))
                i.putExtra("altVia", if (r.second) "🏠 Red local" else "🌐 Red remota")
                startActivity(i)
            }
        }.start()
    }

    private fun manage(cam: SavedCam) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(22), dp(22), dp(16))
        box.addView(Ui.label(this, cam.name, 18f, Ui.TEXT, true))

        val edit = Ui.button(this, "✏️  Editar nombre / dirección remota", Ui.BLUE_LIGHT, Ui.BLUE)
        val del = Ui.button(this, "🗑  Eliminar", Ui.RED_LIGHT, Ui.RED)
        val cancel = Ui.button(this, "Cancelar", Color.TRANSPARENT, Ui.MUTED)
        box.addView(edit, Ui.lp(this, 16))
        box.addView(del, Ui.lp(this, 10))
        box.addView(cancel, Ui.lp(this, 4))

        val d = AlertDialog.Builder(this).setView(box).create()
        edit.setOnClickListener {
            d.dismiss()
            editCam(cam)
        }
        del.setOnClickListener {
            d.dismiss()
            val l = Store.load(this)
            l.removeAll { it.url == cam.url }
            Store.save(this, l)
            refreshSaved()
        }
        cancel.setOnClickListener { d.dismiss() }
        Ui.show(this, d)
    }

    private fun editCam(cam: SavedCam) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(22), dp(22), dp(16))

        val n = Ui.field(this, "Nombre", cam.name, false)
        val r = Ui.field(this, "Dirección remota (ej: casa.ddns.net:8554)", cam.remote, false)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END
        val cancel = Ui.button(this, "Cancelar", Color.TRANSPARENT, Ui.MUTED)
        val ok = Ui.button(this, "Guardar", Ui.BLUE, Color.WHITE)
        row.addView(cancel)
        row.addView(ok)

        box.addView(Ui.label(this, "Editar cámara", 18f, Ui.TEXT, true))
        box.addView(Ui.label(this, "La dirección remota se usa cuando no estás en tu red de casa.", 13f, Ui.MUTED))
        box.addView(n, Ui.lp(this, 16))
        box.addView(r, Ui.lp(this, 10))
        box.addView(row, Ui.lp(this, 18))

        val d = AlertDialog.Builder(this).setView(box).create()
        cancel.setOnClickListener { d.dismiss() }
        ok.setOnClickListener {
            val name = n.text.toString().trim().ifEmpty { cam.name }
            Store.upsert(this, SavedCam(name, cam.url, Store.cleanRemote(r.text.toString())))
            d.dismiss()
            refreshSaved()
        }
        Ui.show(this, d)
    }
}
