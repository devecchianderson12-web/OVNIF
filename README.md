# ONVNIF

## Compilar
1. Sube todo el proyecto a un repo de GitHub (rama main).
2. Pestaña Actions > "Build APK" > descarga el artefacto `CamarasONVIF-apk` (adentro está el .apk).

## Usar en casa
1. Conecta el celular al WiFi de tu casa.
2. Pestaña **Buscar en red** > "Buscar cámaras" > toca una cámara > usuario y contraseña > "Ver cámara".
3. Dentro del visor toca **Guardar**. Queda en **Mis cámaras** (se guarda en el celular).

## Verlas desde otra red (trabajo, datos móviles)
Las IP tipo 192.168.x.x solo existen dentro de tu casa. Para verlas desde fuera elige UNA opción:

### Opción A (recomendada, segura): VPN con Tailscale
1. Instala Tailscale en un equipo que quede siempre encendido en casa (PC, Raspberry Pi, etc.) y actívalo como *subnet router* de tu red (ej. 192.168.1.0/24). Guía: https://tailscale.com/kb/1019/subnets
2. Instala Tailscale en el celular con la misma cuenta y mantenlo conectado.
3. En ONVNIF no pongas dirección remota: la IP local de la cámara ya funciona a través de la VPN.

### Opción automática (UPnP)

Al guardar una cámara con la dirección remota vacía, la app le pide al router (UPnP) que abra el puerto RTSP de esa cámara y guarda tu IP pública. Cada vez que abres la cámara estando en casa, renueva la dirección. No funciona si el router tiene UPnP apagado, si el proveedor usa CGNAT (sin IP pública propia) o si tu IP pública cambió mientras estabas fuera. Expone la cámara a internet (ver riesgos abajo).

### Opción B: reenvío de puertos + DDNS (más riesgosa)
1. En el router, reenvía un puerto externo (ej. 8554, TCP) hacia IP_CAMARA:554.
2. Usa tu IP pública o un DDNS gratuito (No-IP, DuckDNS, etc.).
3. En ONVNIF: Mis cámaras > mantén pulsada la cámara > Editar > Dirección remota: `tu-ddns.net:8554`.
4. La app usa la IP local si la cámara responde y la dirección remota si no. Si la elegida falla al reproducir, prueba automáticamente la otra (15 s de espera por intento).

Riesgo de B: expones tus cámaras a internet. Cambia la contraseña de fábrica por una larga y única, y desactiva UPnP. Si puedes, usa la opción A.

Las contraseñas se guardan en el celular en texto simple dentro de la app.
