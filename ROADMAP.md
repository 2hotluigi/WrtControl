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
| Snapshot (la próxima versión): el ACL de LuCI ya no deja `luci.setInitAction`, que la app usa para reiniciar la red tras instalar WireGuard y para arrancar miniupnpd. Usar la alternativa nueva (p. ej. `rc init`) cuando no esté | P0 | `AddonsVmTest` en verde en snapshot |
| Validar el `.nft` generado con `nft -c` en un contenedor | P0 | Falla la CI si una regla no analiza |
| Compilación reproducible del AAR del túnel (Go + gomobile en CI) | P0 | El AAR del repo coincide con el que genera la CI |
| Versión del esquema en el router (`schema_version`), conservar opciones desconocidas y bloquear la escritura si lo configuró una versión más nueva | P0 | Hoy una versión anterior borra los ajustes nuevos al guardar. Pruebas de migración N−1 / N / N+1 |
| Errores legibles: traducir `UbusException` y los fallos de paquetes a mensaje + causa + acción | P0 | Nunca se muestra un nombre de clase ni un código de rpcd |
| Textos a `strings.xml` (español e inglés) | P0 | Sin textos de usuario en el código Kotlin |
| Versión mínima: soporte probado desde 24.10; 23.05 "puede funcionar"; retirar 22.03 cuando no haya más remedio. En 24.10 el ACL de LuCI no deja ver `service list`: saber si un servicio está en marcha por otro camino (p. ej. `rc list`) y deshacer UPnP si miniupnpd no arranca | P0 | Decidido, documentado en el README y reflejado en la pantalla de conexión. La prueba de UPnP que no arranca pasa en 24.10 |
| Usuario rpcd con ACL limitado (no root) que la app crea en la primera conexión | P0 | La app funciona sin la contraseña de root guardada |
| Proceso: PRs pequeños, `versionCode` que suba, firma de release, changelog | P0 | Definición de hecho: pruebas + VM en CI + README + captura de la pantalla nueva |

## Hito 1 · Que se sienta premium (P1)

Cambiar la cara, no el motor.

- **Navegación nueva:** Inicio · Dispositivos · Familia · Seguridad · Más. Desaparece "Paquetes"
  como pestaña; kernel, carga e interfaces pasan a "Router → Detalles técnicos".
- **Inicio = salud de la red:** internet sí/no y desde cuándo, dispositivos conectados, avisos
  (dispositivos nuevos en cuarentena, router sin contraseña, reglas no activas) y accesos rápidos
  ("Pausar a…", "Wi-Fi de invitados").
- **Sistema de diseño propio** en lugar del color dinámico: paleta y tipografía de marca, iconos por
  tipo de dispositivo, animaciones de aplicar/verificar/confirmar, estados vacíos ilustrados.
  Pruebas de capturas (Roborazzi o Paparazzi) desde el primer componente.
- **Puesta en marcha guiada:** detectar la puerta de enlace, comprobar versión y capacidades, poner
  contraseña si no tiene, crear el usuario de la app y explicar qué va a cambiar en el router.
  Después, emparejar otros teléfonos por QR.
- **Dispositivos con identidad:** nombre editable (guardado en el router), icono y fabricante
  (OUI), en línea o no, y acciones: pausar, bloquear, asignar a una persona, fijar IP. Avisar de las
  MAC aleatorias (bit de dirección local) y guiar para fijarlas.
- **Motor de transacciones genérico:** `SafeApply` como plan → vista previa → aplicar → verificar →
  confirmar, para cualquier función, con un único componente de "Aplicando… si algo falla el
  router vuelve atrás solo".
- **Detección de capacidades:** objetos de ubus, ACL, paquetes, radios, RAM y flash. Cada función se
  muestra como disponible, "se instalará" o no compatible.
- **Una sola fuente de estado del router:** un repositorio que sondea de forma coordinada en lugar
  de un `pollingFlow` por pantalla, y sin efectos secundarios dentro del sondeo de Estado.

## Hito 2 · Lo que se espera de un router (P1)

- **Wi-Fi:** nombre y clave, red de invitados aislada con QR para compartirla, horario del Wi-Fi.
- **Centro de seguridad:** contraseña del router, servicios abiertos hacia la WAN, puertos abiertos
  por UPnP, firmware antiguo, reglas activas. Una puntuación y acciones de un toque.
- **Funciones por resultado** en lugar de paquetes: "Entrar desde fuera de casa" (WireGuard),
  "Juegos y videollamadas sin lag" (SQM, midiendo la velocidad en vez de pedirla), "Nombre fijo
  para tu casa" (DDNS), "Bloquear anuncios en toda la red".
- **Copia de seguridad y restauración** de la configuración del router.
- **Control parental:** categorías, plantillas por edad, "permitir este sitio", diagnóstico de
  "¿por qué no carga?", dificultar VPN, horarios por servicio y modo tareas.
- **Acceso remoto:** probarlo con un teléfono de verdad en datos móviles con IPv6.
- **Claves:** generar la del teléfono en el AAR de Go (curve25519 de wireguard-go) en lugar del
  X25519 propio en Kotlin.

## Hito 3 · Nivel entusiasta (P2)

- **Agente opcional en el router** (paquete apk/ipk: plugin rpcd en ucode + su ACL). La app sigue
  funcionando sin él; con él desbloquea historial de dispositivos, historial de caídas de internet,
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
  x86, para probar instalación de paquetes, memoria y rendimiento.
