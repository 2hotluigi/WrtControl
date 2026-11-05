# WrtControl

App Android (Kotlin + Jetpack Compose) para administrar routers **OpenWrt 22.03 o superior**
desde el celular: estado, dispositivos y control parental (perfiles, pausas, horarios y filtro de
contenido). En la siguiente fase: acceso remoto por WireGuard.

Habla con el router por **ubus JSON-RPC** (`/ubus`, el mismo endpoint que usa LuCI). No hay que
instalar nada en el router: basta con LuCI.

## Compilar y probar

```sh
gradlew testDebugUnitTest   # pruebas unitarias (JVM, sin emulador)
gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

La versión debug sugiere `192.168.56.2`, que es la VM de OpenWrt en VirtualBox (red host-only).
El emulador de Android llega a esa red a través del PC.

### Prueba contra la VM con tráfico real

`VmIntegrationTest` usa el código real de la app contra la VM:
- Con un ping, comprueba que una pausa o un horario cortan incluso una conexión ya abierta.
- Comprueba que los horarios usan la hora local del router.
- Con `nslookup` desde el cliente, comprueba que el filtro de contenido bloquea por IPv4, por
  IPv6 y aunque el cliente pregunte a 8.8.8.8.

Se salta si no se define `WRTCONTROL_VM`. Necesita SSH sin contraseña y un cliente de pruebas
dentro del router:

```sh
ssh root@192.168.56.2 'apk add ip-full kmod-veth'          # una vez
ssh root@192.168.56.2 'cat > /tmp/c.sh && sh /tmp/c.sh up' < tools/vm/netns-client.sh
set WRTCONTROL_VM=192.168.56.2
gradlew testDebugUnitTest --tests "*VmIntegrationTest*"
```

El cliente (MAC `02:00:00:00:00:99`) desaparece al reiniciar el router. La prueba deja el router
sin reglas de WrtControl y en UTC.

## Estructura

| Paquete | Qué hace |
|---|---|
| `ubus` | Cliente JSON-RPC. Distingue sesión caducada de permiso denegado (`-32002` es ambiguo y se resuelve con `session.access`). Renueva la sesión automáticamente, con un solo login aunque haya llamadas concurrentes. Abre sesiones aparte para los cambios. |
| `openwrt` | Modelos y llamadas tipadas (`system`, `network.interface`, `luci-rpc`, `uci`, `file`). `buildDeviceList` combina vecinos y concesiones DHCP, incluido el caso de Windows, que usa en DHCPv6 la MAC de otra tarjeta. |
| `parental` | Perfiles, pausas, horarios y filtro de contenido: cómo se guardan, qué configuración de fw4 y dnsmasq generan y cómo se aplican sin arriesgar el acceso (ver abajo). |
| `data` | Router guardado en DataStore. La contraseña se guarda cifrada con AES-GCM y una clave del Android Keystore. |
| `ui` | Compose + Material 3. Los datos se recargan periódicamente y la recarga se detiene cuando la app pasa a segundo plano. |

## Control parental: qué hace en el router

Cada perfil tiene un nombre, sus dispositivos (por MAC), una pausa opcional (con o sin fin),
horarios sin internet (días + hora de inicio y fin, que puede ser del día siguiente) y un filtro
de contenido.

**Dónde se guarda.** En `/etc/config/firewall`, en secciones `wrtcontrol_profile` y
`wrtcontrol_schedule` que fw4 y LuCI ignoran. Así los datos y las reglas se aplican y se
revierten juntos. Todo lo de la app empieza por `wrtc_`.

**Cómo bloquea.**
1. Reglas MARK de fw4 por MAC (`src '*'`, `dest '*'`, `proto all`): marcan el tráfico reenviado
   de esos dispositivos durante la pausa (`stop_date` si tiene fin) o en la franja horaria
   (`weekdays`, `start_time`, `stop_time`). Una franja que cruza la medianoche se parte en dos
   reglas, porque nftables evalúa el día de la semana en cada paquete.
2. Una cadena fija, `wrtcontrol_forward`, con prioridad -1 en el hook forward, que rechaza lo
   marcado. Va **antes** de la cadena forward de fw4, que acepta primero las conexiones ya
   establecidas: así una pausa corta también un vídeo, un juego o un chat en curso. Está en
   `/etc/luci-uploads/wrtcontrol.nft` y se incluye con una sección `include` de tipo nftables.

**Filtro de contenido (DNS), por perfil.** Va por perfil y no para toda la red: los adultos no
cargan con las reglas de los niños. Las funciones futuras (apps bloqueadas, límites por app,
reportes) también son por persona.

- **Servidor DNS propio:** cada perfil con filtro tiene su propia instancia de dnsmasq
  (`wrtc_dns_<id>` en `/etc/config/dhcp`, puerto 5301–5399), con su lista de bloqueo:
  - sitios propios;
  - servicios del catálogo (TikTok, Roblox…);
  - dominios para saltarse el filtro (resolutores DoH conocidos, el canario de Firefox, el relay
    privado de iCloud).
- **Niveles:** las consultas suben a la instancia principal (sin nivel), al DNS para familias de
  Cloudflare (`1.1.1.3`, contenido para adultos) o a CleanBrowsing Family (estricto: además
  búsqueda segura, YouTube restringido y VPN/proxys).
- **Sin DHCP:** cada instancia lleva secciones `dhcp` con `ignore`. Las secciones `dhcp` sin
  `instance` se aplican a todas las instancias; sin esto, una instancia nueva podría ponerse a
  repartir IP si al arrancar no detecta a tiempo el DHCP de la principal.
- **Redirección:** un `redirect` de fw4 por MAC (`family any`) le manda todo el DNS de esos
  dispositivos, por IPv4 e IPv6, al router o a cualquier servidor (8.8.8.8 incluido). Una regla
  MARK corta el DNS sobre TLS/QUIC (puerto 853).
- **Por qué así:** dnsmasq no admite políticas por cliente, y el paquete básico no trae `nftset`.
  Así funciona sin instalar nada.

**Cómo aplica los cambios** (`SafeApply.kt`):
1. Abre una sesión propia. Los cambios UCI pendientes son por sesión y el ACL de LuCI no deja
   hacer `uci revert`, así que si algo falla basta con abandonarla.
2. Comprueba que no haya otro `uci apply` sin confirmar (de LuCI o de otro teléfono). Mientras
   lo haya, rpcd devuelve esa sesión a cualquier login del mismo usuario, y la app terminaría
   confirmando un cambio ajeno.
3. Aplica con `uci apply` y reversión automática a los 60 s.
4. Verifica en el router que todo cargó:
   - con `nft --json list ruleset`, que fw4 recargó y que están exactamente las reglas esperadas;
   - con `service list`, que cada servidor DNS de perfil está en marcha;
   - con `nslookup`, que el canario da NXDOMAIN y que el DNS para familias contesta.

   Si todo está bien, confirma. Si no, hace `uci rollback` y comprueba desde otra sesión que de
   verdad se deshizo antes de decirlo. Si el DNS para familias no responde desde el router, lo
   explica.

**Permisos.** Solo usa lo que el ACL de LuCI ya permite: UCI de `firewall`, `dhcp` y `system`,
escribir en `/etc/luci-uploads`, el listado del firewall y de servicios de las páginas de estado
y el `nslookup` de la página de diagnóstico. No necesita paquetes extra.

**Hora del router.** fw4 escribe las horas en hora local y nftables las pasa a UTC al cargar las
reglas, con la zona de `/etc/TZ`. Si la zona del router no coincide con la del teléfono, la app lo
avisa y la puede cambiar. Después recarga el firewall para que las reglas se conviertan con la zona nueva.

**Compatibilidad comprobada** en el código de fw4, rpcd y nftables de 22.03 (fw4 de 2022-10-18,
nftables 1.0.2) y probada en la VM con 25.12.

**Límites conocidos.**
- Se identifica a cada dispositivo por su MAC. Si un teléfono usa una MAC aleatoria que cambia
  (la "dirección privada rotativa" de iOS, o la aleatoria no persistente de Android), se
  escapa del perfil. Hay que fijar la MAC para la red de casa.
- Con la descarga de flujos activada (flow offloading), las conexiones abiertas antes de la pausa
  pueden seguir hasta cerrarse. La app lo avisa.
- Si el router cambia de horario de verano, las reglas siguen con el desfase anterior hasta la
  siguiente recarga del firewall (cualquier cambio desde la app la provoca).
- Al eliminar el último perfil, la cadena `wrtcontrol_forward` queda vacía hasta que se reinicie
  el firewall: fw4 recarga con `flush table`, que vacía las cadenas pero no las borra. No hace nada.
- **Filtro de contenido:**
  - **Se puede saltar con:** una VPN o un DNS sobre HTTPS a un servidor que no esté en la lista
    (el nivel estricto bloquea las VPN y proxys conocidos).
  - **Servidor DNS dentro de la LAN:** si el dispositivo usa uno (un Pi-hole en el mismo bridge),
    ese tráfico no pasa por el firewall del router y no se redirige.
  - **Caché:** los dispositivos pueden recordar respuestas anteriores hasta que caduquen, unos
    minutos.
  - **Memoria:** cada perfil con filtro usa una instancia más de dnsmasq, 1–2 MB de RAM.
  - **Privacidad:** con nivel de adultos o estricto, las consultas DNS de ese perfil pasan por
    Cloudflare o CleanBrowsing.

## Hoja de ruta

1. ~~Base: conexión, estado y dispositivos~~
2. ~~Control parental~~
   - ~~Perfiles por persona, pausar internet y horarios (con reversión automática)~~
   - ~~Filtro de contenido por perfil: niveles, servicios y sitios propios~~
   - Más adelante: un perfil "todos los dispositivos" para filtrar toda la red, límites de tiempo
     por servicio y reportes
3. Acceso remoto: túnel WireGuard integrado en la app y configuración del router desde la app
4. Seguridad: usuario rpcd con ACL limitado (no root) y emparejamiento por QR
