package com.camaras.onvif

import android.net.Uri
import android.util.Base64
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class AuthException : Exception("Usuario o contraseña incorrectos")

object Onvif {

    /** Devuelve las URLs RTSP (con credenciales) de los perfiles de video de la camara. */
    fun streams(cam: Camera, user: String, pass: String): List<String> {
        val eps = LinkedHashSet<String>()
        cam.xaddr?.let { eps.add(fixHttp(it, cam.ip)) }
        if (!cam.onvif || eps.isEmpty()) {
            for (h in listOf("", ":8080", ":2020", ":8899", ":8000")) {
                eps.add("http://${cam.ip}$h/onvif/device_service")
            }
        }
        var err = "ONVIF no respondió"
        for (ep in eps) {
            try {
                return fetch(ep, cam.ip, user, pass)
            } catch (e: AuthException) {
                throw e
            } catch (e: Exception) {
                err = e.message ?: err
            }
        }
        throw Exception(err)
    }

    /** Rutas RTSP comunes por si ONVIF falla. */
    fun fallback(ip: String, user: String, pass: String): List<String> {
        val cred = if (user.isNotEmpty()) "${Uri.encode(user)}:${Uri.encode(pass)}@" else ""
        val paths = listOf(
            "/stream1", "/stream2", "/live/ch0", "/live",
            "/h264Preview_01_main", "/h264Preview_01_sub",
            "/cam/realmonitor?channel=1&subtype=0",
            "/Streaming/Channels/101", "/Streaming/Channels/102",
            "/11", "/12", "/onvif1", "/live.sdp", "/"
        )
        return paths.map { "rtsp://$cred$ip:554$it" }
    }

    private fun fetch(ep: String, ip: String, user: String, pass: String): List<String> {
        val off = try {
            timeOffset(ep)
        } catch (e: Exception) {
            if (e is IOException) throw e
            0L
        }
        var media = ep
        try {
            val caps = soap(
                ep,
                """<GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl"><Category>Media</Category></GetCapabilities>""",
                user, pass, off
            )
            Regex("<(?:\\w+:)?Media>.*?<(?:\\w+:)?XAddr>([^<]+)</", RegexOption.DOT_MATCHES_ALL)
                .find(caps)?.groupValues?.get(1)?.let { media = fixHttp(it.trim(), ip) }
        } catch (e: AuthException) {
            throw e
        } catch (e: Exception) {
            // usar el mismo endpoint
        }
        val prof = soap(media, """<GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>""", user, pass, off)
        val tokens = Regex("<(?:\\w+:)?Profiles[^>]*token=\"([^\"]+)\"").findAll(prof)
            .map { it.groupValues[1] }.toList()
        if (tokens.isEmpty()) throw Exception("Sin perfiles de video")
        val out = ArrayList<String>()
        for (t in tokens) {
            try {
                val body = """<GetStreamUri xmlns="http://www.onvif.org/ver10/media/wsdl"><StreamSetup><Stream xmlns="http://www.onvif.org/ver10/schema">RTP-Unicast</Stream><Transport xmlns="http://www.onvif.org/ver10/schema"><Protocol>RTSP</Protocol></Transport></StreamSetup><ProfileToken>$t</ProfileToken></GetStreamUri>"""
                val resp = soap(media, body, user, pass, off)
                val uri = tag(resp, "Uri")
                if (uri != null) out.add(buildRtsp(uri.replace("&amp;", "&"), ip, user, pass))
            } catch (e: AuthException) {
                throw e
            } catch (e: Exception) {
                // siguiente perfil
            }
        }
        if (out.isEmpty()) throw Exception("Sin URL de stream")
        return out.distinct()
    }

    private fun timeOffset(ep: String): Long {
        val resp = soap(ep, """<GetSystemDateAndTime xmlns="http://www.onvif.org/ver10/device/wsdl"/>""", "", "", 0L)
        val block = Regex("UTCDateTime>(.*?)</(?:\\w+:)?UTCDateTime>", RegexOption.DOT_MATCHES_ALL)
            .find(resp)?.groupValues?.get(1) ?: return 0L
        fun n(name: String): Int = tag(block, name)?.trim()?.toIntOrNull() ?: return 0
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(n("Year"), n("Month") - 1, n("Day"), n("Hour"), n("Minute"), n("Second"))
        return cal.timeInMillis - System.currentTimeMillis()
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name>\\s*([^<]*?)\\s*</(?:\\w+:)?$name>").find(xml)?.groupValues?.get(1)

    private fun security(user: String, pass: String, off: Long): String {
        val nonce = ByteArray(16)
        SecureRandom().nextBytes(nonce)
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val created = fmt.format(Date(System.currentTimeMillis() + off))
        val md = MessageDigest.getInstance("SHA-1")
        md.update(nonce)
        md.update(created.toByteArray(Charsets.UTF_8))
        md.update(pass.toByteArray(Charsets.UTF_8))
        val digest = Base64.encodeToString(md.digest(), Base64.NO_WRAP)
        val nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP)
        val u = user.replace("&", "&amp;").replace("<", "&lt;")
        return """<Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"><UsernameToken><Username>$u</Username><Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</Password><Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceB64</Nonce><Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created></UsernameToken></Security>"""
    }

    private fun soap(url: String, body: String, user: String, pass: String, off: Long): String {
        val header = if (user.isNotEmpty()) security(user, pass, off) else ""
        val xml = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"><s:Header>$header</s:Header><s:Body>$body</s:Body></s:Envelope>"""
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 2500
            c.readTimeout = 5000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
            c.outputStream.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401 || text.contains("NotAuthorized")) throw AuthException()
            if (code !in 200..299) throw Exception("HTTP $code")
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun buildRtsp(uri: String, ip: String, user: String, pass: String): String {
        val m = Regex("^rtsp://(?:[^@/]*@)?([^/:]+)(?::(\\d+))?(.*)\$").find(uri) ?: return uri
        val port = m.groupValues[2]
        val rest = m.groupValues[3]
        val cred = if (user.isNotEmpty()) "${Uri.encode(user)}:${Uri.encode(pass)}@" else ""
        val p = if (port.isNotEmpty()) ":$port" else ""
        return "rtsp://$cred$ip$p$rest"
    }

    private fun fixHttp(url: String, ip: String): String {
        val m = Regex("^http://([^/:]+)(?::(\\d+))?(.*)\$").find(url) ?: return url
        val port = m.groupValues[2]
        val p = if (port.isNotEmpty()) ":$port" else ""
        return "http://$ip$p${m.groupValues[3]}"
    }
}
