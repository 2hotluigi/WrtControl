# WrtControl

App Android (Kotlin + Jetpack Compose) para administrar routers **OpenWrt 22.03 o superior**
desde el celular: estado, dispositivos y control parental (perfiles, pausas, horarios con tiempo
extra, filtro de contenido con búsqueda segura, dispositivos nuevos en cuarentena y protección
contra los intentos de saltárselo). En la siguiente fase: acceso remoto por WireGuard.

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
- Comprueba la protección: la cuarentena, la búsqueda segura y YouTube restringido, el DNS
  cifrado por IP, el acceso al router, el corte de una conexión TCP abierta y el tiempo extra.

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

Si en la VM está el `nft` de OpenWrt 22.03 en `/tmp/nft102` (paquetes `nftables-json`,
`libnftnl11`, `libmnl0` y `jansson4` de 22.03.7 para x86_64), la prueba pasa también el fichero
de reglas por su analizador. Esa copia se cuelga al hablar con el kernel de 25.12, pero analiza
bien: basta con que llegue al final del fichero sin errores.

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
horarios sin internet (días + hora de inicio y fin, que puede ser del día siguiente), tiempo
extra opcional, un filtro de contenido y la opción de impedir que sus dispositivos abran la
configuración del router. Además hay un ajuste para toda la red: dispositivos nuevos en
cuarentena.

**Dónde se guarda.** En `/etc/config/firewall`, en secciones `wrtcontrol_profile`,
`wrtcontrol_schedule` y `wrtcontrol_settings` que fw4 y LuCI ignoran. Así los datos y las reglas se aplican y se
revierten juntos. Todo lo de la app empieza por `wrtc_`.

**Cómo bloquea.**
1. Reglas MARK de fw4 por MAC (`src '*'`, `dest '*'`, `proto all`): marcan el tráfico reenviado
   de esos dispositivos durante la pausa (`stop_date` si tiene fin) o en la franja horaria
   (`weekdays`, `start_time`, `stop_time`). Una franja que cruza la medianoche se parte en dos
   reglas, porque nftables evalúa el día de la semana en cada paquete.
2. Reglas propias en un fichero que fw4 incluye en su tabla (sección `include` de tipo
   nftables). La cadena `wrtcontrol_forward` tiene prioridad -1 en el hook forward y rechaza lo
   marcado. Va **antes** de la cadena forward de fw4, que acepta primero las conexiones ya
   establecidas: así una pausa corta también un vídeo, un juego o un chat en curso.
3. El tiempo extra ("30 minutos más", "hasta el fin del horario") pone `start_date` en las
   reglas de los horarios: el router los vuelve a aplicar solo, a su hora.

**Las reglas propias** (`NftInclude.kt`) están en `/etc/luci-uploads/wrtcontrol-<versión>.nft`.
El nombre cambia con el contenido, así que `uci apply` con reversión también restaura las reglas:
vuelve a apuntar al fichero anterior, que no se borra hasta confirmar. La primera regla lleva la
versión en el comentario, para comprobar que fw4 cargó justo ese fichero. No usa sets con nombre:
fw4 recarga con `flush table`, que vacía las cadenas pero no los sets, así que una MAC quitada
seguiría dentro. Sin `{ }` vacíos, que en nftables 1.0.2 son un error de sintaxis. Contiene:

- **Cuarentena.** Lo que viene de una MAC no aprobada no sale de la red.
  - Una regla MARK de fw4 por cada zona de casa (las que reenvían hacia otra zona y tienen algún
    dispositivo) marca lo que entra por ella, y otra por cada zona con NAT marca lo que entra de
    internet. fw4 las regenera con los dispositivos de cada zona en cada recarga.
  - Se rechaza lo que lleva la marca de casa sin la de internet, y solo en el sentido en que se
    abrió la conexión. Las respuestas que llegan de internet nunca coinciden. Si una zona de casa se
    queda sin dispositivos (un Wi-Fi de invitados apagado), fw4 genera su regla sin interfaz y marca
    todo; lo que entra de internet sigue pasando, porque también lleva la otra marca: falla abierta,
    no cerrada.
  - DHCP y DNS van al router (input): el dispositivo se conecta y aparece en la app para aprobarlo.
  - La primera vez se aprueba lo que el router conoce en ese momento. Al volver a activarla, no:
    lo que se conectó mientras tanto tiene que aprobarse.
  - Un dispositivo que sale de un perfil (al borrarlo o quitarlo de él) sigue aprobado.
  - Una zona de casa sin dispositivos al aplicar (Wi-Fi apagado) queda fuera hasta el siguiente
    cambio.
  - Cubre los dispositivos nuevos y las MAC aleatorias.
- **DNS cifrado por IP.** Los perfiles con filtro no llegan por TCP ni UDP, en ningún puerto, a
  los resolutores públicos: Google, Cloudflare, Quad9, NextDNS, AdGuard, OpenDNS, CleanBrowsing,
  Mullvad y Control D. Así no pasan DoH, DoT ni DNSCrypt. El 53 ya va redirigido al router, y el
  ICMP pasa: hay equipos que comprueban la conexión con un ping a 8.8.8.8. Solo rangos dedicados a
  DNS, para no bloquear webs normales. Los intentos se cuentan y la app los muestra.
- **Corte al endurecer el filtro.** Sin esto, un vídeo de TikTok seguiría después de bloquear
  TikTok.
  - Cada conexión nueva de un perfil con filtro lleva la época del perfil en el ct mark (bits
    `0xC000`, libres en mwan3, pbr y los esquemas de DSCP habituales).
  - Al bloquear algo más cambia la época, y las conexiones TCP y QUIC abiertas antes se rechazan.
  - El dispositivo puede reconectar con la IP que tiene en caché, sin preguntar al DNS. Por eso las
    conexiones de los 90 s siguientes llevan otra marca y se cortan otra vez al acabar
    (`meta time`, hora local del router). Para entonces su caché ya caducó: las instancias DNS de
    los perfiles dan como mucho 60 s (`max_ttl`). Si el dispositivo acaba de entrar en el filtro
    (perfil nuevo, filtro nuevo o dispositivo añadido), su caché viene del DNS principal con TTL
    largos y la ventana dura 5 min y medio.
  - La época nueva nunca es la marca de una ventana anterior: lo abierto en ella se sigue cortando.
  - El resto de UDP no se toca: un flujo que reutiliza el mismo puerto (una consola) seguiría en
    conntrack mientras envíe y quedaría cortado indefinidamente.
- **Acceso al router.** Una cadena input con prioridad -1 impide a los perfiles protegidos abrir
  LuCI, entrar por SSH o usar la app (los puertos se leen de uhttpd y dropbear). Los teléfonos desde
  los que se ha usado la app quedan guardados como administradores (`list admin`) y nunca se
  bloquean, aunque el cambio lo haga otro teléfono. Se ven, se quitan y se añaden por MAC en
  Protección → "Teléfonos con WrtControl".

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
  Cloudflare (`1.1.1.3`: contenido para adultos y búsqueda segura de Google, Bing y DuckDuckGo) o
  a CleanBrowsing Family (estricto: además YouTube restringido y VPN/proxys).
- **Búsqueda segura y YouTube restringido**, también sin nivel, como documenta Google:
  - secciones `cname` y `hostrecord` con `instance` (solo para esa instancia) que llevan
    `www.google.*` (los 187 dominios de Google) a `forcesafesearch.google.com` y los nombres de
    YouTube a `restrict(moderate).youtube.com`;
  - IPv4 e IPv6: con solo IPv4, la consulta AAAA se reenvía y la búsqueda segura se escapa por
    IPv6;
  - Bing y DuckDuckGo se preguntan al DNS para familias de Cloudflare, que responde con su versión
    segura;
  - antes de confirmar, la app comprueba que el DNS del perfil da las direcciones de Google.
- **Proveedor que intercepta el DNS:** si el proveedor de internet desvía las consultas a
  1.1.1.3 o a CleanBrowsing hacia su propio servidor, todo "responde" pero no se filtra nada. Antes
  de confirmar, la app comprueba que el DNS del perfil da las respuestas filtradas
  (`forcesafesearch.google.com` para www.google.com o `strict.bing.com` para www.bing.com). Si no,
  no lo activa y lo explica.
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
   - con `nslookup`, que el canario da NXDOMAIN, que el DNS para familias contesta y que la
     búsqueda segura da las direcciones de Google.

   Si todo está bien, confirma. Si no, hace `uci rollback` y comprueba desde otra sesión que de
   verdad se deshizo antes de decirlo. Si el DNS para familias no responde desde el router, lo
   explica.

**Actualizaciones del router.** Una actualización de firmware que conserva la configuración no
guarda `/etc/luci-uploads`. Si falta el fichero de reglas, fw4 lo ignora y la app avisa de que las
reglas no están activas. "Volver a aplicar" (o cualquier cambio) lo repone. El fichero en uso no
se reescribe nunca en su sitio: rpcd trunca y escribe, y un corte a mitad lo dejaría roto. También
avisa si faltan reglas de los perfiles, por ejemplo si se borraron desde LuCI.

**Permisos.** Solo usa lo que el ACL de LuCI ya permite y no necesita paquetes extra:
- UCI de `firewall`, `dhcp` y `system`, y lectura de `uhttpd` y `dropbear`;
- escribir y listar `/etc/luci-uploads`;
- el listado del firewall, de servicios y de interfaces de las páginas de estado;
- el `nslookup` de la página de diagnóstico;
- `luci.setPassword`, para poner contraseña al router.

**Protección del propio control.**
- **Router sin contraseña:** la app avisa (cualquiera en la red podría abrir LuCI y quitar las
  reglas) y permite ponerle una.
- **Copia en el teléfono:** la app guarda una copia de los perfiles y los ajustes por router (su
  dirección, su modelo y la MAC de su red de casa). Si el router se restablece o alguien los borra
  desde LuCI, ofrece restaurarlos, y no la sustituye hasta que el usuario la restaure o la descarte
  (o los perfiles vuelvan por otro camino).
- **Bloqueo de la app:** se puede proteger con la huella, la cara o el bloqueo de pantalla del
  teléfono. Se vuelve a pedir tras un minuto en segundo plano o si Android cierra la app; con la
  app bloqueada no se dibuja ningún diálogo (quedarían encima del candado) y la vista de apps
  recientes no enseña la pantalla.

**Hora del router.** fw4 escribe las horas en hora local y nftables las pasa a UTC al cargar las
reglas, con la zona de `/etc/TZ`. Si la zona del router no coincide con la del teléfono, la app lo
avisa y la puede cambiar. Después recarga el firewall para que las reglas se conviertan con la zona nueva.
Las horas guardadas (fin de una pausa, tiempo extra, segundo corte) son hora local: la app guarda el
desfase con el que las escribió (`time_offset`) y, si la zona cambia, también desde LuCI, las mueve
en el siguiente cambio para que sigan siendo el mismo instante.

**Compatibilidad comprobada** en el código de fw4, rpcd y nftables de 22.03 (fw4 de 2022-10-18,
nftables 1.0.2) y probada en la VM con 25.12.

**Límites conocidos.**
- Se identifica a cada dispositivo por su MAC. Si un teléfono usa una MAC aleatoria que cambia
  (la "dirección privada rotativa" de iOS, o la aleatoria no persistente de Android), se
  escapa del perfil. Hay que fijar la MAC para la red de casa.
- Con la descarga de flujos activada (flow offloading), las conexiones ya abiertas no pasan por
  la cadena forward: pueden seguir hasta cerrarse aunque se pause, se bloquee un servicio, se quite
  una aprobación o se bloquee el DNS cifrado. Las nuevas sí se bloquean. La app lo avisa.
- Si el router cambia de horario de verano, las reglas siguen con el desfase anterior hasta la
  siguiente recarga del firewall (cualquier cambio desde la app la provoca).
- Al eliminar el último perfil, la cadena `wrtcontrol_forward` queda vacía hasta que se reinicie
  el firewall: fw4 recarga con `flush table`, que vacía las cadenas pero no las borra. No hace nada.
- **Versiones distintas de la app:** una versión anterior no conoce los ajustes nuevos y, al guardar,
  los borra (cuarentena, protección, búsqueda segura...). Hay que actualizar la app en todos los
  teléfonos.
- **Cuarentena:**
  - Un dispositivo que copie la MAC de otro ya aprobado pasa como aquel.
  - El tráfico entre equipos de la misma red (el mismo bridge) no pasa por el firewall: la
    cuarentena corta internet, no la red de casa.
  - Hasta que exista el túnel, la app solo avisa de los dispositivos nuevos al abrirla.
- **Filtro de contenido:**
  - **Se puede saltar con:** una VPN o un DNS sobre HTTPS a un servidor que no esté en la lista
    de IP (el nivel estricto bloquea por DNS las VPN y proxys conocidos).
  - **Búsqueda segura:** puede ocultar también vídeos y resultados educativos, y afecta a las
    cuentas de Google Workspace que se usen en esos dispositivos.
  - **Corte al endurecer:** con solo tres marcas, una conexión que no envía nada durante varios
    cambios seguidos puede volver a coincidir con la época actual y seguir. Al pasar un dispositivo
    de un perfil con filtro a otro, sus conexiones pueden coincidir con la época nueva.
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
   - ~~Protección: cuarentena de dispositivos nuevos, DNS cifrado por IP, corte de conexiones al
     bloquear, búsqueda segura y YouTube restringido, acceso al router, tiempo extra, copia de
     los perfiles y bloqueo de la app~~
   - Siguiente: categorías, plantillas por edad, "permitir este sitio", diagnóstico de "¿por qué
     no carga?", avisos en el teléfono, dificultar VPN, horarios por servicio y modo tareas
   - Más adelante: un perfil "todos los dispositivos" para filtrar toda la red y reportes
3. Acceso remoto: túnel WireGuard integrado en la app y configuración del router desde la app
4. Seguridad: usuario rpcd con ACL limitado (no root) y emparejamiento por QR
