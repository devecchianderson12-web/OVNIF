package com.camaras.onvif

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Discovery {

    private fun probe(): String = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl"><e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""

    private fun record(cams: MutableMap<String, Camera>, ip: String, f: (Camera) -> Unit) {
        synchronized(cams) {
            f(cams.getOrPut(ip) { Camera(ip) })
        }
    }

    /** Descubrimiento ONVIF estandar (WS-Discovery, multicast UDP 239.255.255.250:3702). */
    fun wsDiscovery(ctx: Context, timeoutMs: Int, cams: MutableMap<String, Camera>, onChange: () -> Unit) {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("onvif-discovery")
        lock.setReferenceCounted(false)
        lock.acquire()
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket(null)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(0))
            sock.soTimeout = 700
            val group = InetAddress.getByName("239.255.255.250")
            for (i in 0 until 2) {
                val msg = probe().toByteArray(Charsets.UTF_8)
                sock.send(DatagramPacket(msg, msg.size, group, 3702))
            }
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(65535)
            while (System.currentTimeMillis() < end) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    handle(String(p.data, 0, p.length, Charsets.UTF_8), p.address.hostAddress, cams, onChange)
                } catch (e: SocketTimeoutException) {
                    // seguir esperando
                }
            }
        } catch (e: Exception) {
            // ignorar
        } finally {
            try { sock?.close() } catch (e: Exception) {}
            try { lock.release() } catch (e: Exception) {}
        }
    }

    private fun handle(txt: String, ip: String?, cams: MutableMap<String, Camera>, onChange: () -> Unit) {
        if (ip == null || !txt.contains("XAddrs")) return
        val x = Regex("XAddrs[^>]*>([^<]+)<").find(txt)?.groupValues?.get(1)?.trim()
            ?.split(Regex("\\s+"))?.firstOrNull { it.startsWith("http://") }
        val name = Regex("onvif://www\\.onvif\\.org/name/([^\\s<]+)").find(txt)?.groupValues?.get(1)?.let {
            try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
        } ?: ""
        record(cams, ip) { c ->
            c.onvif = true
            if (x != null) c.xaddr = x
            if (name.isNotEmpty()) c.name = name
        }
        onChange()
    }

    /** Respaldo: busca en la subred (x.x.x.1-254) puertos tipicos de camaras. */
    fun portScan(cams: MutableMap<String, Camera>, onChange: () -> Unit) {
        val base = localPrefix() ?: return
        val ports = intArrayOf(554, 2020, 8899)
        val pool = Executors.newFixedThreadPool(64)
        for (i in 1..254) {
            val ip = "$base.$i"
            for (port in ports) {
                pool.execute {
                    if (isOpen(ip, port)) {
                        record(cams, ip) { c ->
                            c.ports.add(port)
                            if ((port == 2020 || port == 8899) && c.xaddr == null) {
                                c.xaddr = "http://$ip:$port/onvif/device_service"
                            }
                        }
                        onChange()
                    }
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)
    }

    private fun isOpen(ip: String, port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), 500)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun localPrefix(): String? {
        var fallback: String? = null
        val ifs = NetworkInterface.getNetworkInterfaces() ?: return null
        for (ni in ifs) {
            if (!ni.isUp || ni.isLoopback) continue
            for (addr in ni.inetAddresses) {
                if (addr is Inet4Address && addr.isSiteLocalAddress) {
                    val parts = (addr.hostAddress ?: continue).split(".")
                    if (parts.size != 4) continue
                    val prefix = parts.take(3).joinToString(".")
                    if (ni.name.startsWith("wlan")) return prefix
                    if (fallback == null) fallback = prefix
                }
            }
        }
        return fallback
    }
}
