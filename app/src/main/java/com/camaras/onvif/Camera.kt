package com.camaras.onvif

class Camera(val ip: String) {
    var xaddr: String? = null
    var onvif: Boolean = false
    var name: String = ""
    val ports: MutableSet<Int> = java.util.TreeSet()

    override fun toString(): String {
        val sb = StringBuilder(ip)
        if (name.isNotBlank()) sb.append("  ·  ").append(name)
        sb.append("\n")
        sb.append(if (onvif) "ONVIF (WS-Discovery)" else "Posible cámara")
        if (ports.isNotEmpty()) sb.append(" · puertos ").append(ports.joinToString(","))
        return sb.toString()
    }
}
