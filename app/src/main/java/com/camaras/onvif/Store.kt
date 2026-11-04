package com.camaras.onvif

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

data class SavedCam(val name: String, val url: String, val remote: String, val auto: Boolean = false)

object Store {
    private const val PREF = "saved_cams"
    private val RX = Regex("^rtsp://([^@/]*@)?([^/:]+)(?::(\\d+))?(.*)\$")

    fun load(ctx: Context): MutableList<SavedCam> {
        val out = ArrayList<SavedCam>()
        val s = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("list", "[]") ?: "[]"
        try {
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(SavedCam(o.optString("name"), o.optString("url"), o.optString("remote"), o.optBoolean("auto", false)))
            }
        } catch (e: Exception) {
            // lista corrupta: ignorar
        }
        return out
    }

    fun save(ctx: Context, list: List<SavedCam>) {
        val a = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("name", c.name)
            o.put("url", c.url)
            o.put("remote", c.remote)
            o.put("auto", c.auto)
            a.put(o)
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("list", a.toString()).apply()
    }

    @Synchronized
    fun upsert(ctx: Context, cam: SavedCam) {
        val l = load(ctx)
        val idx = l.indexOfFirst { it.url == cam.url }
        if (idx >= 0) l[idx] = cam else l.add(cam)
        save(ctx, l)
    }

    fun host(url: String): String = RX.find(url)?.groupValues?.get(2) ?: ""

    fun port(url: String): Int = RX.find(url)?.groupValues?.get(3)?.toIntOrNull() ?: 554

    fun cleanRemote(s: String): String = s.trim().removePrefix("rtsp://").trimEnd('/')

    /** Misma ruta y credenciales, pero apuntando a la direccion remota (host o host:puerto). */
    fun withRemote(url: String, remote: String): String {
        val m = RX.find(url) ?: return url
        return "rtsp://" + m.groupValues[1] + remote + m.groupValues[4]
    }

    private fun reachable(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** La otra ruta posible (remota si se eligio la local y viceversa), o "" si no hay. */
    fun alternate(cam: SavedCam, primaryIsRemote: Boolean): String {
        if (primaryIsRemote) return cam.url
        return if (cam.remote.isNotBlank()) withRemote(cam.url, cam.remote) else ""
    }

    /** Elige la URL local si la camara responde; si no, la remota (si existe). */
    fun pick(cam: SavedCam): Pair<String, Boolean> {
        val host = host(cam.url)
        if (host.isNotEmpty() && reachable(host, port(cam.url), 2500)) return Pair(cam.url, false)
        if (cam.remote.isNotBlank()) return Pair(withRemote(cam.url, cam.remote), true)
        return Pair(cam.url, false)
    }
}
