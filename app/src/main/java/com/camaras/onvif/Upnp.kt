package com.camaras.onvif

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** Abre sola el puerto RTSP de la camara en el router (UPnP/IGD) y calcula la direccion remota. */
object Upnp {

    class Result(val ok: Boolean, val remote: String, val msg: String)

    private class Gateway(val control: String, val type: String)

    private val TARGETS = listOf(
        "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
        "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
        "upnp:rootdevice"
    )

    private fun wifi(ctx: Context): Network? {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun ssdp(ctx: Context, net: Network?): List<String> {
        val found = LinkedHashSet<String>()
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wm.createMulticastLock("onvnif-upnp")
        lock.setReferenceCounted(false)
        lock.acquire()
        try {
            DatagramSocket().use { sock ->
                net?.bindSocket(sock)
                sock.soTimeout = 700
                val group = InetAddress.getByName("239.255.255.250")
                for (st in TARGETS) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                    val b = msg.toByteArray(Charsets.UTF_8)
                    sock.send(DatagramPacket(b, b.size, group, 1900))
                }
                val end = System.currentTimeMillis() + 3500
                val buf = ByteArray(2048)
                var router = false
                while (!router && System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        sock.receive(p)
                        val txt = String(p.data, 0, p.length, Charsets.UTF_8)
                        Regex("(?im)^location:\\s*(\\S+)").find(txt)?.groupValues?.get(1)?.let { found.add(it) }
                        if (txt.contains("InternetGatewayDevice")) router = true
                    } catch (e: SocketTimeoutException) {
                        // seguir esperando hasta el limite
                    }
                }
            }
        } catch (e: Exception) {
            // sin respuestas
        } finally {
            lock.release()
        }
        return found.toList()
    }

    private fun conn(net: Network?, url: String): HttpURLConnection {
        val u = URL(url)
        return (net?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
    }

    private fun httpGet(net: Network?, url: String): String {
        val c = conn(net, url)
        try {
            c.connectTimeout = 2500
            c.readTimeout = 4000
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name>\\s*([^<]*?)\\s*</(?:\\w+:)?$name>").find(xml)?.groupValues?.get(1)

    private fun gateway(net: Network?, location: String): Gateway? {
        val xml = httpGet(net, location)
        val base = tag(xml, "URLBase")?.takeIf { it.isNotBlank() } ?: location
        for (m in Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val block = m.groupValues[1]
            val type = tag(block, "serviceType") ?: continue
            if (!type.contains("WANIPConnection") && !type.contains("WANPPPConnection")) continue
            val ctl = tag(block, "controlURL") ?: continue
            return Gateway(URL(URL(base), ctl).toString(), type)
        }
        return null
    }

    private fun soap(net: Network?, gw: Gateway, action: String, args: String): String {
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:$action xmlns:u=\"${gw.type}\">$args</u:$action></s:Body></s:Envelope>"
        val c = conn(net, gw.control)
        try {
            c.connectTimeout = 3000
            c.readTimeout = 5000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPAction", "\"${gw.type}#$action\"")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val d = tag(text, "errorDescription") ?: "HTTP $code"
                val n = tag(text, "errorCode")
                throw Exception(if (n != null) "$d ($n)" else d)
            }
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun mapArgs(extPort: Int, camIp: String, camPort: Int, lease: Int): String =
        "<NewRemoteHost></NewRemoteHost><NewExternalPort>$extPort</NewExternalPort><NewProtocol>TCP</NewProtocol>" +
            "<NewInternalPort>$camPort</NewInternalPort><NewInternalClient>$camIp</NewInternalClient>" +
            "<NewEnabled>1</NewEnabled><NewPortMappingDescription>ONVNIF</NewPortMappingDescription>" +
            "<NewLeaseDuration>$lease</NewLeaseDuration>"

    private fun isPublic(ip: String): Boolean {
        val p = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        val a = p[0]
        val b = p[1]
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false
        if (a == 169 && b == 254) return false
        if (a == 172 && b in 16..31) return false
        if (a == 192 && b == 168) return false
        if (a == 100 && b in 64..127) return false
        return true
    }

    /** Bloqueante: llamar desde un hilo y desde la red WiFi de casa. */
    fun open(ctx: Context, camIp: String, camPort: Int): Result {
        val octets = camIp.split(".").mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return Result(false, "", "Acceso remoto automático: la cámara necesita IP local")
        val net = wifi(ctx)

        var gw: Gateway? = null
        for (loc in ssdp(ctx, net)) {
            gw = try {
                gateway(net, loc)
            } catch (e: Exception) {
                null
            }
            if (gw != null) break
        }
        val g = gw ?: return Result(false, "", "No encontré el router (UPnP apagado o no disponible)")

        val ext = try {
            tag(soap(net, g, "GetExternalIPAddress", ""), "NewExternalIPAddress") ?: ""
        } catch (e: Exception) {
            ""
        }
        if (ext.isEmpty()) return Result(false, "", "El router no informó su IP pública")
        if (!isPublic(ext)) return Result(false, "", "Sin IP pública ($ext): CGNAT del proveedor o doble router")

        val extPort = 18000 + octets[3]
        try {
            soap(net, g, "AddPortMapping", mapArgs(extPort, camIp, camPort, 0))
        } catch (e: Exception) {
            try {
                soap(net, g, "AddPortMapping", mapArgs(extPort, camIp, camPort, 604800))
            } catch (e2: Exception) {
                val router = try {
                    URL(g.control).host
                } catch (e3: Exception) {
                    "tu router"
                }
                return Result(
                    false, "$ext:$extPort",
                    "El router no permite abrirlo solo (${e2.message}). Una sola vez: entra a $router, " +
                        "sección Reenvío de puertos, y manda el puerto TCP $extPort a $camIp:$camPort. " +
                        "Después la app mantiene tu IP pública actualizada sola."
                )
            }
        }
        return Result(true, "$ext:$extPort", "Acceso remoto activado ($ext:$extPort)")
    }

    /** Abre el puerto y guarda/actualiza la direccion remota (aunque el router no deje abrirlo solo). */
    fun refresh(ctx: Context, cam: SavedCam): Result {
        val r = open(ctx, Store.host(cam.url), Store.port(cam.url))
        if (r.remote.isNotEmpty() && r.remote != cam.remote) {
            Store.upsert(ctx, SavedCam(cam.name, cam.url, r.remote, true))
        }
        return r
    }
}
