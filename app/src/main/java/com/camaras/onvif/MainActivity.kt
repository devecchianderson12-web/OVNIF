package com.camaras.onvif

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class MainActivity : Activity() {

    private val cams = LinkedHashMap<String, Camera>()
    private val shown = ArrayList<Camera>()
    private lateinit var adapter: ArrayAdapter<Camera>
    private lateinit var status: TextView
    private lateinit var btn: Button
    @Volatile private var scanning = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bindWifi()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(16))

        val title = TextView(this)
        title.text = "Cámaras ONVIF"
        title.textSize = 22f

        btn = Button(this)
        btn.text = "Buscar cámaras"
        btn.setOnClickListener { scan() }

        status = TextView(this)
        status.text = "Conéctate al WiFi de tu casa y busca."
        status.setPadding(0, dp(8), 0, dp(8))

        val list = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> ask(shown[pos]) }

        root.addView(title)
        root.addView(btn)
        root.addView(status)
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

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

    private fun scan() {
        if (scanning) return
        scanning = true
        synchronized(cams) { cams.clear() }
        shown.clear()
        adapter.notifyDataSetChanged()
        btn.isEnabled = false
        status.text = "Buscando…"
        Thread {
            val t1 = Thread { Discovery.wsDiscovery(this, 5000, cams) { refresh(false) } }
            val t2 = Thread { Discovery.portScan(cams) { refresh(false) } }
            t1.start()
            t2.start()
            t1.join()
            t2.join()
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
            if (done) {
                scanning = false
                btn.isEnabled = true
                status.text = if (shown.isEmpty())
                    "No se encontraron cámaras. Verifica que estés en el WiFi."
                else
                    "${shown.size} encontradas. Toca una para ver."
            } else {
                status.text = "Buscando… ${shown.size} encontradas"
            }
        }
    }

    private fun ask(cam: Camera) {
        val prefs = getSharedPreferences("cams", MODE_PRIVATE)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)

        val u = EditText(this)
        u.hint = "Usuario"
        u.setText(prefs.getString("user", "admin"))
        u.setSingleLine()

        val p = EditText(this)
        p.hint = "Contraseña"
        p.setText(prefs.getString("pass", ""))
        p.setSingleLine()
        p.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        box.addView(u)
        box.addView(p)

        AlertDialog.Builder(this)
            .setTitle(cam.ip)
            .setView(box)
            .setPositiveButton("Ver") { _, _ ->
                val user = u.text.toString().trim()
                val pass = p.text.toString()
                prefs.edit().putString("user", user).putString("pass", pass).apply()
                open(cam, user, pass)
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
}
