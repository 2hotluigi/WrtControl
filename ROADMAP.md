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

## Hito G · Generalizar y abrir (presentación P0)

Que se entienda desde fuera qué es WrtControl y que funcione en routers que no son el nuestro.
Viene del plan de generalización del 2026-11-18; lo que ya cubrían los Hitos 0 y 1 (capacidades,
cambios seguros, puesta en marcha, errores legibles, español e inglés, credenciales en Keystore)
no se repite aquí.

**Presentación del repo (P0).** Lo primero que ve quien llega desde GitHub.

| Tarea | Prioridad | Hecho cuando |
|---|---|---|
| ~~README en inglés (`README.md`) y en español (`README.es.md`): logo, badges, para qué sirve, capturas, funciones por resultado, "segura por diseño", compatibilidad, instalar, calidad, estado~~ | P0 | Hecho. Lo técnico pasa a `docs/como-funciona.md` y `docs/desarrollo.md` (en español por ahora) |
| ~~Capturas de la app en el README que no se queden viejas~~ | P0 | Hecho: `ReadmeScreenshots` pinta seis pantallas completas en inglés y en español con datos de ejemplo (`docs/screenshots/`); la CI las compara como las demás |
| ~~Logo e icono propios (la app no tenía)~~ | P0 | Hecho: escudo con las ondas del Wi-Fi en azul cobalto; icono adaptativo con versión monocroma y `docs/logo.svg` |
| ~~`CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, plantillas de issue (informe de router, fallo) y aviso de uso responsable~~ | P0 | Hecho |
| ~~`AGENTS.md` / `CLAUDE.md` con las convenciones del proyecto~~ | P0 | Hecho |
| ~~Activar en GitHub el aviso privado de vulnerabilidades, la descripción y los temas del repo, y una imagen para compartirlo (social preview)~~ | P0 | Hecho: el dueño lo activó en Settings. Comprobado por la API de búsqueda que el repo tiene descripción y diez temas (`openwrt`, `android`, `kotlin`…); `SECURITY.md` enlaza al formulario privado |
| Traducir `docs/` al inglés | P2 | Cuando alguien de fuera lo pida o vaya a colaborar |
| ~~Guía de inicio rápido con una VM de OpenWrt lista (un comando que la levante)~~ | P1 | Hecho: "Probarla sin router" en el README. `tools/vm/demo.sh` (Linux, macOS) y `tools/vm/demo.ps1` (Windows) bajan la imagen oficial x86-64 de la última estable (comprobada con su sha256) y la arrancan en QEMU sin sudo, con la web en el puerto 8080 del PC y una contraseña aleatoria puesta antes de dejarse ver desde la red. El trabajo `demo` de la CI arranca, apaga y vuelve a arrancar `demo.sh` y pasa `DemoRouterTest`. En Windows, con QEMU 11.1 recién instalado: descarga y primer arranque en unos 2 minutos, y el APK publicado (0.3.0) en el emulador entra por `10.0.2.2:8080`, crea un perfil y pausa internet. Falta que lo pruebe alguien de fuera para medir los 15 minutos |

**Que funcione en otros routers (P1).**

| Tarea | Prioridad | Hecho cuando |
|---|---|---|
| ~~Red de casa sin suponer que se llama `lan`: hoy el Wi-Fi, Dispositivos y la identificación del router buscan la interfaz por ese nombre~~ | P1 | Hecho: `HomeNetwork.kt` la saca del cortafuegos (zona sin NAT, que deja entrar al router y sale a internet; fuera las de la app y una de invitados hecha a mano), con `lan` primero y de reserva. Dispositivos usa todas las redes del lado de dentro menos el acceso remoto. Probado con `HomeNetworkTest` y `HomeNetworkVmTest`, que renombra `lan` a `casa` en la VM (interfaz, zona, reglas, DHCP y Wi-Fi): la app la encuentra, identifica el mismo router y sigue viendo al cliente de pruebas. El Wi-Fi renombrado, solo con pruebas unitarias (la VM no tiene radios) |
| ~~Etiqueta por función en "Estado": probada en este router, no probada o no soportada (hoy es por versión de OpenWrt)~~ | P1 | Hecho: "funciona aquí" (la app la activó y la comprobó con `applySafely`, o la ve en marcha: `addons/InUse.kt`, `parental/Verified.kt`), "sin probar aquí" con si la versión está probada, "se instalará" o "no compatible". Se guarda por router en el teléfono (`RouterStore.verified`). Probado con `InUseTest`, `VerifiedTest` (contra el router simulado), `RouterStoreTest` y la captura de Estado; en el emulador contra el GL-MT6000 sale "funciona aquí" en acceso remoto, nombre fijo, SQM y UPnP (en marcha) y "sin probar aquí" en control parental y filtro (sin usar) |
| ~~Informe de dispositivo: exportar desde la app, anonimizado (sin MAC, IP, nombres, claves ni redes), las respuestas de ubus y UCI que lee la app~~ | P1 | Hecho: "Compartir informe" en Más → Router (`DeviceReport.kt`): respuestas de ubus y 10 configuraciones de UCI, sin los aparatos de la casa, el registro ni las redes vecinas, con seudónimos coherentes (`ReportAnonymizer.kt`) y una revisión final que no deja compartir si algo se escapó. `DeviceReportTest` (datos de ejemplo llenos de lo que no debe salir), `DeviceReportVmTest` y, contra el GL-MT6000, ninguna de sus 26 MAC, IPv6, ULA, DUID, 10 claves de WireGuard ni lo del DNS dinámico apareció. La plantilla de informe de router de GitHub pide adjuntarlo. Por el camino, `IcuRegexTest`: ICU (Android) no compila `[:` en una expresión y la app se cerraba |
| Pruebas de contrato contra esos informes: cada router que alguien aporta amplía la matriz | P1 | Al menos tres informes de routers distintos pasan las pruebas |
| Más variantes de VM en la CI: PPPoE, DSA con VLAN, sin Wi-Fi, solo punto de acceso, y un "proveedor" con y sin prefijo IPv6 delegado | P2 | Cada variante con sus pruebas en `vm.yml` |

**Apertura gradual (P2).** Criterio de cierre: un router de un tercero funciona sin tocar el código.

1. Alpha privada con 2 o 3 personas con routers distintos (criterio: completan la conexión sin ayuda).
2. Hilo en el foro de OpenWrt pidiendo informes de router.
3. IzzyOnDroid y después F-Droid (las releases firmadas de GitHub ya existen desde la 0.2.0).
4. Play Store al final, con prueba cerrada. El túnel no usa `VpnService`, así que no hace falta esa
   declaración; revisar los permisos de red local y de cámara, y los requisitos vigentes al publicar.

Señales de que está listo para enseñarse: un router ajeno funciona sin tocar el código, ningún
cambio deja sin acceso al router, lo no disponible se explica, hay una tabla de routers y
versiones probados, y el README deja a un desconocido entender y probar el proyecto.

## Hito 0 · Cimientos (P0)

Corto y antes que nada: que la calidad no dependa de acordarse de lanzar las pruebas.

| Tarea | Prioridad | Hecho cuando |
|---|---|---|
| ~~CI en GitHub Actions: `testDebugUnitTest`, Android lint, detekt/ktlint, kover y `go test ./tunnel`~~ | P0 | Hecho: cada PR los ejecuta y `master` exige `android` y `tunnel` en verde |
| ~~Matriz de OpenWrt en CI: imágenes x86-64 en QEMU (24.10, 25.12, snapshot) con `VmIntegrationTest` y `AddonsVmTest`~~ | P0 | Hecho: cada noche y en los PR que tocan `parental/`, `addons/`, `openwrt/` o `ubus/` (`.github/workflows/vm.yml`) |
| ~~Sin `luci.setInitAction` en el ACL de LuCI (snapshot y ya algunas compilaciones de 25.12.6, como la del GL-MT6000): la app lo usa para reiniciar la red tras instalar WireGuard y para arrancar miniupnpd~~ | P0 | Hecho: si se deniega, `rc init`. Probado en un GL-MT6000 con 25.12.6 |
| ~~Análisis de seguridad en GitHub: CodeQL y Dependabot~~ | P1 | Hecho: CodeQL con la configuración por defecto (Actions, Go, Kotlin y Python) en cada PR, en `master` y cada semana. Dependabot avisa de las vulnerabilidades y, con `.github/dependabot.yml`, abre cada semana PR agrupados para Gradle, el túnel (Go) y las acciones de la CI. Las 9 alertas de Gradle venían del classpath del build (AGP 9.5.1, Kover 0.9.11 y lint) y ninguna iba dentro del APK (comprobado con `releaseRuntimeClasspath`). Como no hay una versión estable de esos plugins que las suba, `build-seguras` (`libs.versions.toml`) fija versiones mínimas en el classpath del build y en `androidLintTool`. Comprobado con `buildEnvironment` y `:app:dependencies`, pruebas, lint, detekt, informe de Kover y release firmada. Quitarlas cuando el plugin ya pida una versión igual o mayor |
| ~~Validar el `.nft` generado con `nft -c` en un contenedor~~ | P0 | Hecho: trabajo `nft` de la CI. Los ejemplos de `NftSamplesTest` (uno por función y uno con todo) pasan por `nft -c` y `fw4 check` en las imágenes `openwrt/rootfs` de 22.03, 23.05, 24.10, 25.12 y snapshot |
| ~~Compilación reproducible del AAR del túnel (Go + gomobile en CI)~~ | P0 | Hecho: trabajo `tunnel-aar`. Versiones fijadas (Go y gomobile de `go.mod`, NDK, JDK) y, sin rutas, `buildid`, `modinfo` ni la firma del clang, el AAR de Windows y el de Linux coinciden fichero a fichero |
| ~~Versión del esquema en el router (`schema_version`), conservar opciones desconocidas y bloquear la escritura si lo configuró una versión más nueva~~ | P0 | Hecho: `option schema` en `wrtc_settings` (sin ella, la 1). Se conservan las opciones y las secciones de datos desconocidas. Con un esquema más nuevo, solo lectura (ni guardar, ni zona horaria, ni restaurar copias) y un aviso para actualizar. Pruebas N−1 / N / N+1 en `SchemaTest`. Falta lo mismo para los complementos (DNS dinámico, acceso remoto, SQM, UPnP) |
| ~~Errores legibles: traducir `UbusException` y los fallos de paquetes a mensaje + causa + acción~~ | P0 | Hecho: un único traductor (`describeError`) para los errores de ubus, de red (por causa: no responde, rechaza, sin camino, nombre, TLS) y de la app. Los que ya hablan al usuario se marcan con `UserFacing`. Una prueba recorre todos y falla si aparece un nombre de clase, un objeto de ubus o un código |
| ~~Textos a `strings.xml` (español e inglés)~~ | P0 | Hecho en dos fases: las pantallas, y la lógica, los ViewModels y los formatos con `Txt` (recurso y datos, sin `Context`). `HardcodedTextTest` vigila las pantallas; las pruebas JVM leen los textos con `TestStrings`. Quedan en español, a propósito, los nombres de las reglas que la app escribe en el router (se ven en LuCI) |
| ~~Versión mínima: soporte probado desde 24.10; 23.05 "puede funcionar"; retirar 22.03 cuando no haya más remedio. En 24.10 el ACL de LuCI no deja ver `service list`: saber si un servicio está en marcha por otro camino (p. ej. `rc list`) y deshacer UPnP si miniupnpd no arranca~~ | P0 | Hecho: tabla en el README; la pantalla de conexión pide 24.10 y Estado avisa con 22.03/23.05 o anteriores. UPnP usa `rc list` si no hay `service list`, y su prueba de VM ya no se salta en 24.10 |
| ~~Permiso de red local de Android 17 (`ACCESS_LOCAL_NETWORK`, en tiempo de ejecución, para apps con `targetSdk` 37): sin él, Android corta toda conexión a IPs privadas (EPERM en UDP, TCP que no conecta), así que la app no llega al router en casa. Pedirlo antes de conectar y explicar para qué~~ | P0 | Hecho: pantalla antes de la app (dar permiso, ir a los ajustes si Android ya no pregunta, o seguir sin él), y los fallos de conexión lo explican. Probado en el emulador API 37 y en un teléfono con Android 17 |
| Usuario rpcd con ACL limitado (no root) que la app crea en la primera conexión | P0 → Hito 3 | **Se hará con el agente.** Comprobado en el GL-MT6000 (25.12.6): root por ubus no puede crearlo. Ningún grupo de LuCI da la config `rpcd` (`uci get rpcd` da "permiso denegado" incluso a root), ni deja escribir en `/usr/share/rpcd/acl.d`. Lo demás ya se sabe: rpcd lee los usuarios en cada login (no hay que reiniciarlo), acepta un hash `$5$` (SHA-256-crypt) calculado en el teléfono, y con los grupos de LuCI basta para todo lo que llama la app. No es una barrera ante quien tenga su contraseña: DNS dinámico (`ip_script`), firewall (includes de script) y dnsmasq (`dhcpscript`) escritos por UCI dejan ejecutar como root |
| ~~Proceso: PRs pequeños, `versionCode` que suba, firma de release, changelog~~ | P0 | Hecho: `wrtc.version` en `gradle.properties` (de ahí, `versionCode`), firma por secrets o propiedades, `CHANGELOG.md`, plantilla de PR con la definición de hecho y `release.yml` al etiquetar `vX.Y.Z`. La clave y los secrets `WRTC_KEYSTORE_*` están creados: `release.yml` publicó firmadas la 0.2.0, la 0.3.0, la 0.4.0 y la 0.5.0 |

## Hito 1 · Que se sienta premium (P1)

Cambiar la cara, no el motor.

Orden: ~~una sola fuente de estado~~ → ~~detección de capacidades~~ → sistema de diseño → ~~navegación
nueva e Inicio~~ → ~~salud del cable~~ → dispositivos con identidad → motor de transacciones → puesta en
marcha guiada. Cada uno se apoya en los anteriores.

- ~~**Navegación nueva:** Inicio · Dispositivos · Familia · Seguridad · Más. Desaparece "Paquetes"
  como pestaña; kernel, carga e interfaces pasan a "Router → Detalles técnicos".~~
  Hecho: Familia son los perfiles y sus avisos; Seguridad, la cuarentena, la contraseña del router
  y el bloqueo de la app; Más lleva a "Funciones del router" (los paquetes), a "Router" (los
  detalles técnicos) y a desconectar.
- ~~**Inicio = salud de la red:** internet sí/no y desde cuándo, dispositivos conectados, avisos
  (dispositivos nuevos en cuarentena, router sin contraseña, reglas no activas) y accesos rápidos
  ("Pausar a…", "Wi-Fi de invitados").~~
  Hecho: "Pausar a…" lleva a Familia, y dos tarjetas llevan al Wi-Fi (su nombre y cuántos hay
  conectados, o si está apagado por el horario o lo dan otros aparatos) y al Wi-Fi de invitados.
- ~~**Salud del cable de internet**~~ (hecho: `WanLink`. El enlace, la velocidad y las caídas salen
  de `luci-rpc.getNetworkDevices`; lo que anuncia cada lado, de `network.device.status`; la hora de
  cada caída, del registro (`log.read`), y un ping desde el router dice si de verdad hay internet.
  Inicio avisa con qué pasa y qué hacer. Probado en el GL-MT6000; el caso del cable dañado, con
  datos de ejemplo.) Como se planteó:
  **Salud del cable de internet:** caídas del enlace WAN y velocidad negociada (netifd por ubus:
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
  - **Lenguaje visual nuevo** (2026-11-24, ver `docs/diseno.md`): la interfaz se sentía "Material
    de serie". Listas agrupadas sobre fondo negro (o gris claro), cifras grandes, controles
    redondos, fichas en pantalla y navegación flotante de 3 pestañas más Seguridad; lo de Más pasa
    a una cuadrícula en Inicio. Aprobado sobre una maqueta (`ProposalScreenshotTest`). Por partes:
    - ~~Paleta y componentes (`ui/design`)~~: hecho. El esquema de Material sale de la paleta
      nueva, así que todas las pantallas ya tienen sus colores; capturas regrabadas.
    - ~~Navegación flotante y Más dentro de Inicio~~: hecho. `AppFrame`: título centrado con la
      dirección debajo, engranaje de Ajustes (Router, Añadir otro teléfono, Desconectar) y píldora
      flotante con Inicio, Dispositivos y Familia, más Seguridad en el botón redondo. Wi-Fi,
      Invitados, Consumo y Funciones, en una cuadrícula al final de Inicio. Las capturas del README
      usan el mismo marco.
    - Inicio.
    - Dispositivos y la ficha de un dispositivo (pantalla en vez de diálogo).
    - Familia y Seguridad.
    - Uso de internet, Wi-Fi, Invitados, Funciones del router y Router.
- **Puesta en marcha guiada:** detectar la puerta de enlace, comprobar versión y capacidades, poner
  contraseña si no tiene, crear el usuario de la app y explicar qué va a cambiar en el router.
  Después, emparejar otros teléfonos por QR.
  Primera parte hecha: la pantalla de conexión propone la puerta de enlace del Wi-Fi y, la primera
  vez que un teléfono entra en un router, cuatro pasos antes de la app: qué router es y si su
  versión está probada, poner contraseña si no tiene, qué puede hacer y qué va a cambiar. Probado
  en el emulador contra el GL-MT6000.
  Segunda parte hecha: "Añadir otro teléfono" enseña un código QR con la dirección y el usuario
  (nunca la contraseña); la cámara del otro teléfono abre la app con eso rellenado.
  El usuario de la app va con el agente (Hito 3).
- **Dispositivos con identidad**, primera parte hecha: fabricante por el prefijo de la MAC con una
  lista que va dentro de la app (290 KB, de los registros públicos del IEEE vía la lista `manuf` de
  Wireshark; `tools/update-oui.py` la regenera), aviso de MAC privada, icono por clase de aparato y
  "Aparato Samsung" en vez de "Sin nombre". Medido en la casa: de 16 equipos, 4 no decían su
  nombre y los 4 tienen fabricante conocido; ninguno usaba MAC privada. Los nombres por DHCPv6 y
  las pistas de host no resolvieron ninguno de esos 4.
  Segunda parte hecha: nombre editable guardado en el router (`wrtc_name` en las secciones `host`
  de `dhcp`), dirección fija, de quién es, en línea o no (tabla de vecinos) y la misma identidad
  en Familia y Seguridad.
  Tercera parte hecha: pausar y bloquear un aparato suelto (pausas por aparato en los ajustes del
  control parental, esquema 2; probado en las VM y en el GL-MT6000).
  Cuarta parte hecha: mDNS para los que no dicen su nombre. El router le pregunta a cada uno
  directamente (puerto 5353) con su `nslookup`, sin instalar nada; `umdns` se descartó porque su
  objeto de ubus no entra en el ACL de LuCI. Medido en la casa: de los 4 sin nombre contestó uno
  (una caja de tele con Google Cast). Queda para más adelante preguntar por multidifusión desde el
  teléfono (`NsdManager`), que alcanzaría a los que no contestan a preguntas directas, y leer el
  nombre puesto a un Chromecast (`fn=` del TXT), que BusyBox no enseña.
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
  Hecho: `applySafely` da sus fases (aplicar, comprobar, confirmar, deshacer) por el contexto de
  la corrutina, `ChangeRunner` lleva el cambio en curso de cada pantalla y `ApplyingBanner` lo
  enseña igual en Familia, Seguridad, Dispositivos y Funciones del router.
  La vista previa ("esto es lo que va a cambiar") sale solo donde hay algo que avisar antes
  (`ChangeRunner.propose`, `ChangePreviewDialog`): dirección fija, de quién es un aparato,
  bloquearlo, zona horaria y contraseña del router. Lo que se enseña lo calcula la app con lo que
  ya leyó; falta que salga del plan real del router (el diff de UCI) y usarla en el Wi-Fi, donde
  el aviso es que el teléfono se desconecta.
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
  Por partes:
  - ~~*Invitados:* red aparte, aislada, con QR.~~ Hecho: Más → Wi-Fi de invitados (red, reparto,
    zona que solo sale a internet y una red por radio), probado con tráfico real en el GL-MT6000.
    Borrarlo del todo desde la app, hecho (`GuestVmTest` lo comprueba con la huella de la
    configuración). Falta probarlo con un teléfono de verdad (el QR y la unión por Wi-Fi) e IPv6
    para invitados: lo normal en OpenWrt es `ip6assign 64` en su interfaz, con `ra`/`dhcpv6`
    `server` y reglas para ICMPv6 y DHCPv6, pero solo da IPv6 con internet si el proveedor delega
    un prefijo. Probado el 2026-11-18 en el GL-MT6000 (Megacable no delega; IPv6 en relay): netifd
    no asigna nada a la red de invitados, ni siquiera de la ULA del router, y las VM de la CI
    tampoco tienen prefijo delegado. Se hará cuando haya dónde probarlo (una VM con un servidor
    DHCPv6-PD delante).
  - ~~*Horario del Wi-Fi.*~~ Hecho: franjas sin Wi-Fi en Más → Wi-Fi, con cron (el ACL de LuCI
    deja escribir el crontab de root), `wifi down`/`wifi up` y "Encender ahora". Probado contra el
    GL-MT6000 (`WifiScheduleVmTest`). Falta verlo apagar y encender un Wi-Fi que emita de verdad
    (el del router de pruebas está apagado).
  - ~~*Lo básico:* encender o apagar cada banda, nombre, clave y seguridad (WPA2/WPA3 mixto por
    defecto; avisar de una red abierta, como la `OpenWrt` sin clave que trae el router de fábrica).
    Un cambio de Wi-Fi puede dejar fuera al teléfono que lo hace: `SafeApply` tiene que esperar a
    que el teléfono vuelva a entrar antes de confirmar.~~
    Hecho: Más → Wi-Fi, un solo nombre y clave para las dos bandas, vista previa de quién se
    queda fuera y, si es este teléfono, 3 minutos (en vez de 1) para volver a entrar antes de que
    el router lo deshaga. Probado en el GL-MT6000 con la red `WrtControl-prueba` (2,4 y 5 GHz) y
    vuelto a dejar como estaba desde la app. Falta probar el caso del teléfono conectado por ese
    Wi-Fi con un teléfono de verdad. Hecho después: al cambiar el nombre, la clave o la seguridad,
    "Guardar en este teléfono" (`ACTION_WIFI_ADD_NETWORKS`, Android 11+; antes, los ajustes de
    Wi-Fi), ofrecido desde que empieza el cambio para que el teléfono pueda volver a entrar.
  - ~~*País:* sin `country`, el router emite con los canales y la potencia más restrictivos. Pedirlo
    en la puesta en marcha (proponer el del teléfono).~~
    Hecho: Más → Wi-Fi avisa si falta, propone el del teléfono y deja elegir entre los que conoce
    el router; si el Wi-Fi no vuelve a emitir con el país nuevo, se deshace. La puesta en marcha
    lo pide en un paso propio, solo si el router tiene Wi-Fi sin país.
  - *Canal y ancho por resultado*, no por número: "más alcance" o "más velocidad" (20/40 MHz en
    2,4 GHz; 80 o 160 MHz en 5 GHz, explicando DFS: el router puede tardar un minuto en emitir y
    cambiar de canal si detecta un radar). Buscar el canal menos ocupado (`iwinfo scan` por ubus).
    Primera parte hecha: "Velocidad y alcance" en Más → Wi-Fi, con lo recomendado o lo más rápido
    en cada banda (solo lo que la radio admite), y "Buscar el más libre": el router escanea y la
    app propone el canal con menos vecinos (1, 6 u 11; 36 o 149). Hecho.
  - ~~*Potencia* y lista de equipos conectados por banda con su señal (ya está en `iwinfo`).~~
    Hecho: potencia en tres niveles (toda, media, baja) y "Conectados ahora" con la señal en
    palabras. La lista con señal no se ha visto con aparatos de verdad (el Wi-Fi del router de
    pruebas está apagado).
  - ~~*Wi-Fi apagado a propósito:* si lo dan otros puntos de acceso (en la casa de pruebas, dos Deco
    en modo AP), decirlo en vez de avisar de "Wi-Fi apagado".~~ Hecho: si el router no emite y
    este teléfono está por Wi-Fi en la red de casa (su puerta de enlace es el router), ese Wi-Fi
    lo da otro aparato; Inicio y Más → Wi-Fi lo dicen así. Falta verlo en un teléfono de verdad en
    la casa (el emulador no está en ese Wi-Fi).
  - *Roaming* (802.11r/k/v) queda en el Hito 3, con varios puntos de acceso.
- **Rendimiento ("Que el router dé todo lo que puede"):** una pantalla que mira qué hay y propone,
  en vez de una lista de interruptores. Contrastado el 2026-11-16 con las recomendaciones de la
  página del GL-MT6000 en openwrt.org y con el router de la casa.
  - *Reparto entre núcleos* (`network.globals.packet_steering`): la app no lo enseña ni lo cambia.
    La página recomienda "todos los núcleos" (`2`) en routers de 4 núcleos, sobre todo con SQM;
    `1` reparte menos. Se aplica con `/etc/init.d/packet_steering reload`, sin reiniciar la red
    (hecho a mano en la casa, que estaba en `1`). Con la descarga por hardware no importa.
  - *irqbalance* (`luci-app-irqbalance`): según la página no da más velocidad, solo ayuda a otras
    tareas. No ofrecerlo salvo que el router haga algo más que enrutar (Docker, Samba).
  - *Descarga de flujos* (`flow_offloading` y `flow_offloading_hw` en el firewall): la app solo
    avisa de que rompe los cortes del control parental. Falta poder activarla y quitarla, y
    elegir por el usuario: es incompatible con SQM (lo que se descarga no pasa por `cake`) y con
    cortar conexiones abiertas. Recomendarla solo sin SQM ni control parental y con una línea más
    rápida de lo que la CPU mueve sola; por hardware solo donde el chip la tenga (Filogic, MT7621).
  - *WED* (la descarga del Wi-Fi en MediaTek, `wed_enable` de `mt7915e`): solo sirve con el Wi-Fi
    del router en uso. Se salta AQL, así que da más velocidad pero puede dar más latencia en el
    Wi-Fi. Necesita escribir en `/etc/modules.conf` y reiniciar: con el agente (Hito 3).
  - *AQL* (límites de cola del Wi-Fi, `aql_txq_limit` en debugfs): bajarlos a 1500–2500 da menos
    latencia y algo menos de velocidad punta; no vale con WED. Se pierde al reiniciar (va en
    `/etc/rc.local`): con el agente, como "Wi-Fi para juegos y videollamadas".
  - *Beamforming y BSS coloring:* vienen activados; la página pide no tocarlos. No enseñarlos.
  - *SQM bien medido:* ya está (cake 285/285 en la casa: 263/262 Mbit/s y la misma latencia con
    carga). Falta avisar cuando la línea supera lo que la CPU puede moldear, y ofrecer entonces
    quitar SQM y activar la descarga.
  - *Temperatura y carga* en "Router → Detalles técnicos" (`thermal_zone`; 41 °C en la casa).
  - *Luz del router:* apagarla o atenuarla ("modo noche"), con las secciones `led` de
    `/etc/config/system`, que el ACL de LuCI ya deja escribir.
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
- **Uso de la red:** cuánto internet se gasta en las últimas 24 h, 7 días y 30 días (recibido,
  enviado y total) y qué dispositivo gasta más, como en Firewalla. Los paquetes son opcionales: se
  ofrecen solo si caben en el router (espacio libre y `lowMemory`) y la app funciona igual sin
  ellos. Comprobado a mano en la VM (25.12.6) el 2026-11-18, por JSON-RPC como root, con tráfico
  de un cliente de la LAN; los ACL de los dos paquetes son idénticos en 24.10, 25.12 y master.
  Unos 3 MB de RAM y 2,4 MB de flash entre los dos (sobre todo sqlite, libgd y libjpeg). Tres PR:
  - ~~*Sin instalar nada:* bytes recibidos y enviados desde que arrancó el router
    (`network.device status` de la WAN) y la gráfica propia (24 h, 7 días, 30 días; totales debajo),
    con capturas de Roborazzi en claro y oscuro.~~ Hecho: Más → Uso de internet con recibido,
    enviado, total y media al día desde el arranque (`stats` de la tarjeta física de la WAN en
    `luci-rpc getNetworkDevices`, no `pppoe-wan`; `network.device status` no entra en el ACL de LuCI
    de 24.10), y
    `UsageChart` (barras por hora o día, recibido abajo y enviado encima, tocar una barra la
    detalla, colores comprobados para daltonismo en claro y oscuro), que se verá cuando haya
    historial (vnStat). `UsageTest`, capturas `uso-de-internet*` y `UsageVmTest` (en la CI con 24.10,
    25.12 y snapshot, y en la VM 25.12.6:
    cifras iguales a las del kernel y suben con tráfico de prueba por la WAN). En el emulador contra
    el GL-MT6000: 186/73,7 GiB en 7 días, lo mismo que `/sys/class/net/eth1/statistics`.
  - ~~*vnStat 2* (`luci-app-vnstat2`), instalado desde la app como SQM: la gráfica de la WAN. Guarda
    48 h en tramos de 5 min, 4 días por horas, 62 por días y 25 meses. El ACL de lectura solo deja
    `vnstat --json f 1`, pero el de escritura deja `/usr/bin/vnstat` con cualquier argumento
    (`vnstat --json h 24`, `d 30`). Viene sin interfaz: poner la de la WAN en `vnstat.interface`
    (UCI) leyéndola del router; falta probar PPPoE (`pppoe-wan`). La base va en `/var/lib`, que es
    RAM: se pierde al reiniciar. `/etc/vnstat.conf` no se puede leer ni escribir con el ACL, así
    que una línea del crontab (`# wrtc_vnstat`, idempotente) pone `DatabaseDir /etc/wrtc_vnstat`
    y `SaveInterval 60` (una escritura por hora en la flash) y reinicia vnStat. Probado: se aplica
    en menos de un minuto y los datos sobreviven al reinicio. Hacerlo al instalar, con la base
    vacía. El usuario limitado del agente (Hito 3) necesitará el grupo de escritura del paquete.~~
    Hecho: "Instalar" en Más → Uso de internet (si quedan 4 MB libres) instala el paquete, añade la
    tarjeta física de la WAN con `applySafely` y deja la base en `/etc/wrtc_vnstat` con una línea de
    cron que se borra sola; cron y el crontab quedan como estaban. Si ya estaba instalado, la tarjeta
    "Configurar" hace lo mismo sin instalar. La gráfica se llena con `--json h 24` y `d 30`. Por el
    camino: el cron de BusyBox corta la línea en el primer `#` aunque vaya entre comillas, así que la
    orden no lleva ninguno. `VnstatTest`, capturas `uso-de-internet-*` y `UsageVmTest` (en la VM
    25.12.6 desde cero: instala, guarda en la flash cada hora, el crontab y cron como estaban, y la
    hora en curso lleva el tráfico de prueba). En el GL-MT6000 desde el emulador: instalado en menos
    de un minuto (3,2 MB), sin crontab y con cron parado como antes, y la gráfica con la primera hora.
    Ahí salió que `created` trae la fecha pero no la hora (la gráfica empezaba a medianoche): sale de
    su timestamp con la diferencia horaria del router. PPPoE sigue sin probar.
  - ~~*nlbwmon* (`luci-app-nlbwmon`): consumo por dispositivo del periodo (el mes) con
    `nlbwmon-action download -g mac,ip` y `periods` para los anteriores; los nombres, de
    `getHostHints`. En su ficha y en una lista "quién gastó más". Solo da totales por periodo, no
    la curva. Tarda hasta ~2 min en contar (`refresh_interval`). Quitar la entrada de MAC
    `00:00:00:00:00:00`. Guardar en la flash con `database_directory` (UCI) al instalar: si se
    cambia después empieza vacío. Guarda al apagar y cada 24 h; probado que sobrevive al reinicio.~~
    Hecho: "Instalar" en Más → Uso de internet (si queda 1 MB libre) instala el paquete y pone
    `database_directory` en `/etc/wrtc_nlbwmon` y `commit_interval` en `1h` con `applySafely`.
    "Quién gasta más" lista los aparatos de este mes con los nombres de Dispositivos, y la ficha de
    cada uno dice lo suyo. Por el camino: el propio router sale como un aparato (con la IP de su WAN)
    y se quita, y nlbwmon no cuenta el ping (solo TCP y UDP). `NlbwmonTest`, capturas
    `uso-de-internet-aparatos` y `-ofertas`, y `UsageVmTest` (en la VM 25.12.6 desde cero: instala,
    guarda en la flash cada hora y cuenta al cliente de pruebas sin contar al router). Falta: los
    periodos anteriores (`periods`) y el aviso con la descarga de flujos por hardware (abajo).
  - *Descarga de flujos:* por software cuenta bien en los dos (fw4 crea la flowtable con
    `counter`; probado). Por hardware los paquetes no pasan por la CPU y es probable que cuenten de
    menos: comprobarlo en un Filogic. Con SQM no pasa (SQM ya obliga a no usarla, ver *Descarga de
    flujos* arriba); sin SQM y con `flow_offloading_hw`, avisar de que las cifras pueden quedarse
    cortas.
  - Pruebas de VM (`*VmTest`): instalar, generar tráfico con el cliente de pruebas y comprobar que
    se lee, también después de reiniciar.
- **Más paquetes opcionales**, elegidos de las 106 apps de LuCI de 25.12 (101 en 24.10) por lo que
  acercan la app a Firewalla o Deco. Comprobados a mano en la VM (25.12.6) el 2026-11-18 por
  JSON-RPC como root, con un cliente de la LAN. Entre los cinco ocupan 12,5 MB de flash
  (librespeed-cli 9 MB, el resto menos de 150 KB cada uno). Reglas comunes que salieron:
  - Varios se activan solos al instalarse (https-dns-proxy, watchcat). Instalar, dejarlos parados
    al momento y activarlos después con `applySafely`.
  - Un servicio coge los cambios de UCI con `rc init <servicio> restart`, que la app ya puede llamar.
  - Los que tocan dnsmasq o el DNS de la LAN deben quedarse en la instancia principal y no forzar
    el DNS (la app ya lo fuerza): sin eso **rompen el control parental**. https-dns-proxy, con su
    configuración de serie, reescribe los `server` de todas las instancias (`dnsmasq_config_update
    '*'`), lo que quita el DNS para familias de los perfiles. Además pone su redirección del 53
    antes que la de cada perfil, así que el dispositivo filtrado se salta su filtro. Probado y
    arreglado con `force_dns 0` y `dnsmasq_config_update` = la instancia principal. adblock-fast,
    igual (`force_dns 0`, `dnsmasq_instance`).
  - Ofrecerlos según el espacio libre y la memoria del router, y ocultar lo que no exista en 24.10.
  - *Prueba de velocidad desde el router* (`luci-app-librespeed`, **solo 25.12**): **alcanzable,
    P1.** ubus `librespeed`: `start`, `status`, `result` (bajada, subida, ping, jitter, servidor) e
    `history`. Programarla es UCI (`schedule`) más `rc init` (el servicio escribe su línea en el
    crontab a una hora al azar de la franja). El historial va en `/tmp`; `history.archive_path`
    guarda un resumen diario en la flash. Medido: 118,8/185,9 Mbps, ping 47,6 ms, ~600 MB de
    tráfico por prueba (avisar si la línea tiene límite de datos). En automático eligió un
    servidor de otro país: dejar elegir o fijar uno cercano. La respuesta trae la IP pública: no
    mostrarla ni guardarla. Usa servidores de terceros: lo inicia el usuario y se dice.
  - *Amenazas bloqueadas* (`luci-app-banip`): **alcanzable, P1.** Viene apagado. Con dos listas
    (`cinsscore`, `threat`) cargó 14.022 IPs en 1 s, la WAN se detecta sola y el estado sale en
    `/var/run/banIP/banIP.runtime.json` (ACL de lectura). Arrancar con `/etc/init.d/banip restart`
    (ACL de escritura). El contador "Bloqueado" de Firewalla necesita `ban_nftcount 1` (viene a
    0); con él, cada IP cuenta sus paquetes y `banip report json` da el resumen por lista,
    entrante y saliente. Probado: tres conexiones de un dispositivo a una IP de la lista,
    bloqueadas y contadas. Entrante, probado con un "atacante" en la zona wan (`inet-up`): la app
    añade su IP a `/etc/banip/banip.blocklist` con `file.write` (ACL de escritura) y sus pings y
    conexiones TCP al router quedan descartados y contados (11 paquetes, `sum_cntinbound`). Cuenta
    paquetes, no intentos de conexión: decirlo así o agrupar. Falta ver el volumen real en una WAN
    con IP pública.
  - *Bloquear anuncios en toda la casa* (`luci-app-adblock-fast`): **alcanzable, P1.** Viene apagado
    y sin listas activas (trae Hagezi, OISD, StevenBlack...). rpcd propio `luci.adblock-fast`:
    `getInitStatus` (estado, número de dominios, errores) y `setInitAction`. Con Hagezi Pro
    bloqueó 252.767 dominios y el dnsmasq principal pasó de 1 a 20 MB de RAM: en routers de 128 MB,
    solo listas pequeñas. Los perfiles sin nivel de filtro pasan por la instancia principal y
    también quedan sin anuncios; los que tienen DNS para familias van a su servidor y no. La lista
    está en `/var/run` y se vuelve a descargar al arrancar.
  - *DNS privado* (`luci-app-https-dns-proxy`): **alcanzable con cuidado, P1.** rpcd propio
    `luci.https-dns-proxy` (`getInitStatus`, `getProviders` con unos 40 proveedores,
    `setInitAction`). Se activa solo al instalarse (Cloudflare y Google). **Si el DoH no llega, la
    LAN se queda sin DNS** y no vuelve sola al DNS normal (probado: 90 s cortando el 443 del router).
    Va siempre con `applySafely` (resolver por el DoH desde el router antes de confirmar) y un
    vigilante que lo retira si deja de responder. Antes, lo de dnsmasq de arriba.
  - *Recuperarse solo sin internet* (`luci-app-watchcat`): **alcanzable, P1.** Solo UCI. Se activa
    solo al instalarse, en modo "reiniciar el router si no responde 8.8.8.8 en 6 h". Mejor
    `restart_iface` con la WAN: probado que la reinicia tras el periodo sin ping y lo deja escrito
    en el registro, que la app puede enseñar como "internet se recuperó solo a las…".
  - *Encender un equipo* (`luci-app-wol`, 16 KB): **alcanzable, P2.** rpcd `luci.wol`: `stat` dice
    qué herramienta hay y `exec` acepta solo `etherwake`/`wakeonlan` con sus argumentos. Probado:
    el paquete mágico llega al dispositivo de la LAN (capturado). Un botón "Encender" en su ficha;
    con el acceso remoto, desde fuera. Que el equipo despierte depende de su BIOS/tarjeta.
  - *Calidad de la conexión* (`luci-app-apinger`, 70 KB): **parcial, P3.** Viene sin destinos. Con
    uno (UCI `target` + `rc init`), ubus `apinger status` da latencia y pings enviados/recibidos en
    directo, y el registro las alarmas (caída detectada en ~10 s y su vuelta). Pero `loss` sale
    vacío, la alarma de retardo de serie es de 5–20 ms (salta en casi cualquier línea) y no guarda
    historial (solo gráficas PNG). Para "cortes de hoy" da más el registro de watchcat o mwan3.
  - *Historial de dispositivos Wi-Fi* (`luci-app-wifihistory`, **solo 25.12**): **alcanzable sin
    probar con radios, P2.** Leído el código: cada 30 s apunta cada MAC asociada (primera y última
    vez, SSID, nombre e IP) y `luci.wifihistory getHistory` la devuelve. La ruta está fija en
    `/var/lib/wifihistory` (RAM): para conservarla, el truco de cron de vnStat. En la VM no se pudo
    asociar un cliente con `mac80211_hwsim` (hostapd dentro de `ujail` no se registra en ubus, y
    fuera de él el script de la radio le pasa el país `00`); probarlo en un router con Wi-Fi.
  - *Internet de respaldo* (`luci-app-mwan3`): **alcanzable, P2.** Sigue en iptables/ipset
    (`iptables-nft`) también en 25.12. Probado con una segunda WAN de pruebas (un veth a otro
    namespace): `mwan3 status` por ubus da el estado, la latencia y la pérdida de cada WAN; con la
    política `wan_wanb`, al cortar la principal el tráfico de la LAN pasa a la de respaldo y vuelve
    solo al recuperarse, con todo en el registro. Hay que poner `track_ip` y `metric` distintos por
    WAN y elegir la política; la de serie es "balanced". La segunda WAN real (teléfono por USB,
    otro módem) necesita sus drivers. Sus marcas (0x3F00) ya están reservadas en la app.
  - *Un dispositivo por otra salida o VPN* (`luci-app-pbr`, 143 KB): **alcanzable, P2.** Viene
    apagado. rpcd `luci.pbr`: `getInterfaces`, `getInitStatus`, `setInitAction`. Probado: una
    política por IP del cliente lo saca por la segunda WAN y el resto sigue por la principal. Marca
    con `0x00ff0000`, sin pisar la del control parental (comprobar las dos juntas con una prueba de
    VM). Las políticas por dominio necesitan `dnsmasq-full` (nftset). No mezclarlo con mwan3.
  - Descartados: AdGuard
    Home, Tailscale, cloudflared, CrowdSec y NextDNS (binarios de Go de 8 a 30 MB, y repiten lo que
    ya hace la app), `luci-app-statistics` (solo da PNG), Unbound y SmartDNS (sustituyen el DNS).

## Hito 3 · Nivel entusiasta (P2)

- **Agente opcional en el router** (paquete apk/ipk: plugin rpcd en ucode + su ACL). Al instalarlo
  crea el usuario de la app (uno por teléfono, revocable) para no guardar la contraseña de root, y
  ese usuario solo llama a métodos del agente que validan cada valor: sin `ip_script`, includes de
  script ni `dhcpscript`, no puede hacerse root. La app sigue funcionando sin él; con él desbloquea historial de dispositivos, historial de caídas de internet,
  avisos de consumo (el consumo en sí va en el Hito 2, *Uso de la red*) y **notificaciones** (dispositivo nuevo, internet caído) por
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
