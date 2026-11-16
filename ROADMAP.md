# Hoja de ruta de WrtControl

**Objetivo:** llevar a cualquier router OpenWrt la experiencia de la app de TP-Link Deco, Firewalla o
UniFi. Que se pueda usar en lugar de LuCI y se sienta como un sistema robusto, premium y sencillo,
que explique resultados ("entrar desde fuera de casa") en vez de mostrar opciones sin contexto.

**Principios**
- **Nunca deja al usuario sin internet ni sin router.** Todo cambio va con reversión automática,
  verificación en el router y confirmación (`SafeApply`). Es el sello de la app.
- **Empieza sin instalar nada** (el ACL de LuCI basta). Lo avanzado se desbloquea con paquetes o
  con un agente opcional, sin que el usuario tenga que saber qué es un paquete.
- **Lenguaje llano:** cada opción dice qué consigue y qué pasa si se activa. Lo técnico, al final y
  plegado ("Detalles técnicos").
- **Calidad automática:** lo que no prueba la CI contra un OpenWrt real no está terminado.

Prioridades: **P0** antes de cualquier beta pública · **P1** núcleo de la visión · **P2** después ·
**P3** si sobra tiempo.

## Hecho

1. ~~Base: conexión, estado y dispositivos~~
2. ~~Control parental~~
   - ~~Perfiles por persona, pausar internet y horarios (con reversión automática)~~
   - ~~Filtro de contenido por perfil: niveles, servicios y sitios propios~~
   - ~~Protección: cuarentena de dispositivos nuevos, DNS cifrado por IP, corte de conexiones al
     bloquear, búsqueda segura y YouTube restringido, acceso al router, tiempo extra, copia de los
     perfiles y bloqueo de la app~~
3. ~~Paquetes y acceso remoto~~
   - ~~Instalar y configurar paquetes desde la app: WireGuard, DNS dinámico, SQM y UPnP~~
   - ~~Servidor WireGuard en el router, un par por teléfono~~
   - ~~Túnel WireGuard dentro de la app (sin VPN de Android), también en casa: autentica al router~~

## Hito 0 · Cimientos (P0)

Corto y antes que nada: que la calidad no dependa de acordarse de lanzar las pruebas.

| Tarea | Prioridad | Hecho cuando |
|---|---|---|
| ~~CI en GitHub Actions: `testDebugUnitTest`, Android lint, detekt/ktlint, kover y `go test ./tunnel`~~ | P0 | Hecho: cada PR los ejecuta y `master` exige `android` y `tunnel` en verde |
| ~~Matriz de OpenWrt en CI: imágenes x86-64 en QEMU (24.10, 25.12, snapshot) con `VmIntegrationTest` y `AddonsVmTest`~~ | P0 | Hecho: cada noche y en los PR que tocan `parental/`, `addons/`, `openwrt/` o `ubus/` (`.github/workflows/vm.yml`) |
| ~~Sin `luci.setInitAction` en el ACL de LuCI (snapshot y ya algunas compilaciones de 25.12.6, como la del GL-MT6000): la app lo usa para reiniciar la red tras instalar WireGuard y para arrancar miniupnpd~~ | P0 | Hecho: si se deniega, `rc init`. Probado en un GL-MT6000 con 25.12.6 |
| ~~Validar el `.nft` generado con `nft -c` en un contenedor~~ | P0 | Hecho: trabajo `nft` de la CI. Los ejemplos de `NftSamplesTest` (uno por función y uno con todo) pasan por `nft -c` y `fw4 check` en las imágenes `openwrt/rootfs` de 22.03, 23.05, 24.10, 25.12 y snapshot |
| ~~Compilación reproducible del AAR del túnel (Go + gomobile en CI)~~ | P0 | Hecho: trabajo `tunnel-aar`. Versiones fijadas (Go y gomobile de `go.mod`, NDK, JDK) y, sin rutas, `buildid`, `modinfo` ni la firma del clang, el AAR de Windows y el de Linux coinciden fichero a fichero |
| ~~Versión del esquema en el router (`schema_version`), conservar opciones desconocidas y bloquear la escritura si lo configuró una versión más nueva~~ | P0 | Hecho: `option schema` en `wrtc_settings` (sin ella, la 1). Se conservan las opciones y las secciones de datos desconocidas. Con un esquema más nuevo, solo lectura (ni guardar, ni zona horaria, ni restaurar copias) y un aviso para actualizar. Pruebas N−1 / N / N+1 en `SchemaTest`. Falta lo mismo para los complementos (DNS dinámico, acceso remoto, SQM, UPnP) |
| ~~Errores legibles: traducir `UbusException` y los fallos de paquetes a mensaje + causa + acción~~ | P0 | Hecho: un único traductor (`describeError`) para los errores de ubus, de red (por causa: no responde, rechaza, sin camino, nombre, TLS) y de la app. Los que ya hablan al usuario se marcan con `UserFacing`. Una prueba recorre todos y falla si aparece un nombre de clase, un objeto de ubus o un código |
| ~~Textos a `strings.xml` (español e inglés)~~ | P0 | Hecho en dos fases: las pantallas, y la lógica, los ViewModels y los formatos con `Txt` (recurso y datos, sin `Context`). `HardcodedTextTest` vigila las pantallas; las pruebas JVM leen los textos con `TestStrings`. Quedan en español, a propósito, los nombres de las reglas que la app escribe en el router (se ven en LuCI) |
| ~~Versión mínima: soporte probado desde 24.10; 23.05 "puede funcionar"; retirar 22.03 cuando no haya más remedio. En 24.10 el ACL de LuCI no deja ver `service list`: saber si un servicio está en marcha por otro camino (p. ej. `rc list`) y deshacer UPnP si miniupnpd no arranca~~ | P0 | Hecho: tabla en el README; la pantalla de conexión pide 24.10 y Estado avisa con 22.03/23.05 o anteriores. UPnP usa `rc list` si no hay `service list`, y su prueba de VM ya no se salta en 24.10 |
| ~~Permiso de red local de Android 17 (`ACCESS_LOCAL_NETWORK`, en tiempo de ejecución, para apps con `targetSdk` 37): sin él, Android corta toda conexión a IPs privadas (EPERM en UDP, TCP que no conecta), así que la app no llega al router en casa. Pedirlo antes de conectar y explicar para qué~~ | P0 | Hecho: pantalla antes de la app (dar permiso, ir a los ajustes si Android ya no pregunta, o seguir sin él), y los fallos de conexión lo explican. Probado en el emulador API 37 y en un teléfono con Android 17 |
| Usuario rpcd con ACL limitado (no root) que la app crea en la primera conexión | P0 → Hito 3 | **Se hará con el agente.** Comprobado en el GL-MT6000 (25.12.6): root por ubus no puede crearlo. Ningún grupo de LuCI da la config `rpcd` (`uci get rpcd` da "permiso denegado" incluso a root), ni deja escribir en `/usr/share/rpcd/acl.d`. Lo demás ya se sabe: rpcd lee los usuarios en cada login (no hay que reiniciarlo), acepta un hash `$5$` (SHA-256-crypt) calculado en el teléfono, y con los grupos de LuCI basta para todo lo que llama la app. No es una barrera ante quien tenga su contraseña: DNS dinámico (`ip_script`), firewall (includes de script) y dnsmasq (`dhcpscript`) escritos por UCI dejan ejecutar como root |
| ~~Proceso: PRs pequeños, `versionCode` que suba, firma de release, changelog~~ | P0 | Hecho: `wrtc.version` en `gradle.properties` (de ahí, `versionCode`), firma por secrets o propiedades, `CHANGELOG.md`, plantilla de PR con la definición de hecho y `release.yml` al etiquetar `vX.Y.Z`. Falta crear la clave y los secrets |

## Hito 1 · Que se sienta premium (P1)

Cambiar la cara, no el motor.

Orden: ~~una sola fuente de estado~~ → ~~detección de capacidades~~ → sistema de diseño → navegación
nueva e Inicio → salud del cable → dispositivos con identidad → motor de transacciones → puesta en
marcha guiada. Cada uno se apoya en los anteriores.

- **Navegación nueva:** Inicio · Dispositivos · Familia · Seguridad · Más. Desaparece "Paquetes"
  como pestaña; kernel, carga e interfaces pasan a "Router → Detalles técnicos".
- **Inicio = salud de la red:** internet sí/no y desde cuándo, dispositivos conectados, avisos
  (dispositivos nuevos en cuarentena, router sin contraseña, reglas no activas) y accesos rápidos
  ("Pausar a…", "Wi-Fi de invitados").
- **Salud del cable de internet:** caídas del enlace WAN y velocidad negociada (netifd por ubus:
  `carrier`, `speed`; el registro del sistema para contarlas). En el GL-MT6000 un cable con un par
  dañado tiraba la WAN cada pocos minutos y la dejaba a 100 Mbps, y parecía un fallo de la app:
  "tu cable de internet se desconectó 13 veces en 10 minutos y va a 100 Mbps: cámbialo".
- **Sistema de diseño propio** en lugar del color dinámico: paleta y tipografía de marca, iconos por
  tipo de dispositivo, animaciones de aplicar/verificar/confirmar, estados vacíos ilustrados.
  Pruebas de capturas (Roborazzi o Paparazzi) desde el primer componente.
  - ~~Paleta, tipografía y formas de marca~~ (hecho: "Cobalto", elegida entre tres propuestas. Azul
    cobalto, Manrope dentro de la app y colores de estado aparte del de marca, en claro y oscuro).
  - ~~Pruebas de capturas~~ (hecho: Roborazzi; los componentes y Estado, en claro y oscuro, se
    comparan en la CI con las imágenes guardadas).
  - Falta: iconos por tipo de dispositivo, animaciones de aplicar/verificar/confirmar y estados
    vacíos ilustrados. Irán con las pantallas que los usan.
- **Puesta en marcha guiada:** detectar la puerta de enlace, comprobar versión y capacidades, poner
  contraseña si no tiene, crear el usuario de la app y explicar qué va a cambiar en el router.
  Después, emparejar otros teléfonos por QR.
- **Dispositivos con identidad:** nombre editable (guardado en el router), icono y fabricante
  (OUI), en línea o no, y acciones: pausar, bloquear, asignar a una persona, fijar IP. Avisar de las
  MAC aleatorias (bit de dirección local) y guiar para fijarlas.
  - **Investigar: fabricante, MAC aleatoria y nombre** (para no mostrar "Sin nombre"). Por
    comprobar en la investigación:
    - *Fabricante sin conexión:* los registros públicos del IEEE (MA-L, MA-M y MA-S en
      `standards-oui.ieee.org`, CSV gratuitos) o el `manuf` de Wireshark, que se deriva de ellos.
      Revisar la licencia para redistribuirlos, el tamaño en el APK y cómo actualizarlos. Nunca
      mandar las MAC a una API de terceros: dicen qué aparatos hay en la casa.
    - *MAC aleatoria:* el segundo bit del primer byte (`x2`, `x6`, `xA`, `xE`). Con ella no hay
      fabricante: mostrar "MAC privada". Mirar cuándo la rotan Android, iOS y Windows (por red,
      cada cierto tiempo) y qué pasa entonces con la cuarentena y los perfiles.
    - *Nombre:* hoy sale de las concesiones DHCP de IPv4 (opción 12), que muchos aparatos no
      mandan. Otras fuentes: concesiones de DHCPv6 (odhcpd por ubus), mDNS (`umdns` en el router,
      o `NsdManager` desde el teléfono con el permiso de red local), NetBIOS y el nombre de UPnP o
      SSDP. Medir en la casa cuántos "Sin nombre" resuelve cada una. Si no hay ninguna, usar el
      fabricante o el tipo ("Aparato Samsung") hasta que el usuario le ponga nombre.
- **Motor de transacciones genérico:** `SafeApply` como plan → vista previa → aplicar → verificar →
  confirmar, para cualquier función, con un único componente de "Aplicando… si algo falla el
  router vuelve atrás solo".
- ~~**Detección de capacidades:** objetos de ubus, ACL, paquetes, radios, RAM y flash. Cada función se
  muestra como disponible, "se instalará" o no compatible.~~
  Hecho: `Capabilities`, con los permisos de la sesión (los devuelve el login de rpcd), los objetos
  de ubus, `luci.getFeatures`, las radios, la memoria y el espacio libre; sin el listado de
  paquetes. "Estado" enseña cada función con su motivo si no es compatible. Probado en las VM de
  24.10, 25.12 y snapshot (antes y después de instalar WireGuard) y en el GL-MT6000. Queda usarlo
  en la puesta en marcha y en las pantallas nuevas, y avisar con poca memoria cuando haya funciones
  que la necesiten (bloqueo de anuncios).
- ~~**Una sola fuente de estado del router:** un repositorio que sondea de forma coordinada en lugar
  de un `pollingFlow` por pantalla, y sin efectos secundarios dentro del sondeo de Estado.~~
  Hecho: `RouterMonitor`, con una lectura compartida (`Feed`) por dato. Cada una se pide una vez
  aunque la miren varias pantallas, guarda lo último leído y aprovecha las que ya hay (los equipos
  usan las redes que leyó Estado). Apuntar qué router es y sus direcciones públicas sale del sondeo
  (`followRouter`). Una lectura que venía de la sesión anterior ya no cierra la nueva.

## Hito 2 · Lo que se espera de un router (P1)

- **Wi-Fi:** nombre y clave, red de invitados aislada con QR para compartirla, horario del Wi-Fi.
- **Centro de seguridad:** contraseña del router, servicios abiertos hacia la WAN, puertos abiertos
  por UPnP, firmware antiguo, reglas activas. Una puntuación y acciones de un toque.
- **Funciones por resultado** en lugar de paquetes: "Entrar desde fuera de casa" (WireGuard),
  "Juegos y videollamadas sin lag" (SQM, midiendo la velocidad en vez de pedirla, desde el
  teléfono: medida desde el propio router la limita su CPU, 189 Mbit/s en una línea de 300), "Nombre fijo
  para tu casa" (DDNS), "Bloquear anuncios en toda la red".
- **Copia de seguridad y restauración** de la configuración del router.
- **Control parental:** categorías, plantillas por edad, "permitir este sitio", diagnóstico de
  "¿por qué no carga?", dificultar VPN, horarios por servicio y modo tareas.
- **Acceso remoto, que valga para cualquier conexión** (no solo IPv6 tras un módem):
  - ~~El DNS dinámico borra en el proveedor la familia que no publica: DuckDNS deja un A con la IPv4
    de quien crea el nombre, y WireGuard en Windows prefiere ese A viejo~~ (hecho: el teléfono avisa
    al proveedor al guardar; dynv6 no deja borrar el AAAA y se avisa).
  - ~~**"¿Se llega desde fuera?":** saludo de WireGuard por IPv6 y por IPv4 desde los datos móviles
    (`requestNetwork` con `TRANSPORT_CELLULAR`) sin salir del Wi-Fi de casa. Decide qué familias
    publicar y explica el fallo. Sustituye a "probarlo con un teléfono en datos móviles".~~ (hecho:
    el socket se ata a los datos móviles con `bindSocket` y wireguard-go lo usa con un `Bind` propio;
    probado en el emulador. En un teléfono de verdad (Android 17) contra el GL-MT6000: el acceso
    remoto de la app entra por IPv6 desde otra red; la prueba por datos móviles solo IPv4 no puede
    comprobar el IPv6 y ahora lo dice así, en vez de "no se llega". Falta probarla con datos con IPv6.)
  - **Módem del proveedor que filtra lo que entra** (el ONT de Megacable con el cortafuegos por
    defecto descarta todo el IPv6 entrante): detectarlo con la prueba anterior y explicar qué tocar
    (nivel del cortafuegos IPv6, DMZ hacia el router).
  - **Doble NAT** (el router detrás de otro, con la WAN en 192.168.x): hoy no publica IPv4. Si el de
    arriba tiene DMZ o el puerto abierto, publicar la IPv4 pública vista desde internet
    (`ip_source=web`) solo cuando la prueba desde fuera confirme que entra. Con CGNAT, IPv6 o el
    servidor intermedio del Hito 3.
- **IPv6 sin delegación de prefijo** (Megacable y otros dan una sola /64 en la WAN): detectarlo y
  ofrecer relay de la /64 a la LAN (odhcpd ra/dhcpv6/ndp relay y `sourcefilter=0` en wan6), con
  NAT66 de respaldo. Probado a mano en el GL-MT6000. El DNS por IPv6 necesita dos ajustes más:
  - El relay reenvía el RDNSS del módem tal cual (en Megacable `fe80::1`, que en la LAN no
    existe): poner `dhcp.lan.dns` con la ULA del router para que odhcpd lo reescriba. Si no, los
    equipos esperan a que falle antes de usar el DNS de IPv4 y las consultas se saltan dnsmasq.
  - Con `localservice=1`, dnsmasq ignora las consultas desde la /64 relayada (no es una subred
    suya): `localservice=0` con `notinterface=wan`, comprobando antes que la zona wan rechaza la
    entrada. Probado en la casa el 2026-11-16: desde internet el 53 sigue sin responder.
- **Claves:** generar la del teléfono en el AAR de Go (curve25519 de wireguard-go) en lugar del
  X25519 propio en Kotlin.

## Hito 3 · Nivel entusiasta (P2)

- **Agente opcional en el router** (paquete apk/ipk: plugin rpcd en ucode + su ACL). Al instalarlo
  crea el usuario de la app (uno por teléfono, revocable) para no guardar la contraseña de root, y
  ese usuario solo llama a métodos del agente que validan cada valor: sin `ip_script`, includes de
  script ni `dhcpscript`, no puede hacerse root. La app sigue funcionando sin él; con él desbloquea historial de dispositivos, historial de caídas de internet,
  consumo por dispositivo (nlbwmon) y **notificaciones** (dispositivo nuevo, internet caído) por
  ntfy, UnifiedPush o FCM. Con el agente, el aviso de dispositivos nuevos deja de depender de abrir
  la app.
- **Actualizar el firmware** desde la app (Attended SysUpgrade) conservando los paquetes, y volver a
  instalar solo lo que borró la actualización (WireGuard, ddns…).
- **Control parental:** un perfil "todos los dispositivos" para filtrar toda la red, y reportes.
- **Acceso remoto con CGNAT en IPv4:** servidor intermedio si IPv6 no basta.
- **Varios routers y puntos de acceso**, y después mesh y roaming (usteer, 802.11r).
- **HTTPS con certificado fijado** en el primer uso cuando uhttpd tenga TLS.

## Más adelante (P3)

- iOS: separar `ubus`, `openwrt` y `parental` en módulos Kotlin puros, listos para Kotlin
  Multiplatform.
- Widgets y accesos directos (pausar internet, compartir el Wi-Fi de invitados).
- Laboratorio de hardware: un MT7621 de 128 MB, un Filogic (OpenWrt One, AX3000T o GL.iNet) y un
  x86, para probar instalación de paquetes, memoria y rendimiento. Ya hay un GL-MT6000 (Filogic,
  25.12.6) en uso en una casa, al que se llega por un túnel de desarrollo: las pruebas de VM valen
  para él y se saltan lo que borraría una configuración en uso (WireGuard, DNS dinámico, acceso
  remoto, UPnP).
