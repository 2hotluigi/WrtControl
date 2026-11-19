# Desarrollo

Cómo compilar WrtControl, probarlo (también contra OpenWrt de verdad) y publicar una versión.
Para colaborar, ver también [CONTRIBUTING.md](../CONTRIBUTING.md).

## Compilar y probar

```sh
gradlew testDebugUnitTest   # pruebas unitarias (JVM, sin emulador)
gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
gradlew detekt lintDebug    # análisis estático y estilo (ktlint va dentro de detekt)
gradlew koverHtmlReportDebug  # cobertura: app/build/reports/kover/htmlDebug/index.html
```

La CI (`.github/workflows/ci.yml`) ejecuta todo esto y `go test` del túnel en cada PR. Lo que ya
había al activar detekt y lint está en `app/detekt-baseline.xml` y `app/lint-baseline.xml`: la CI
solo falla por problemas nuevos. Al arreglar algo de la línea base, se regenera con
`gradlew detektBaseline` o `gradlew updateLintBaseline`.

El túnel del acceso remoto es Go (`tunnel/`: wireguard-go con netstack) y va ya compilado en
`app/libs/wrtcvpn.aar`, así que la app compila sin Go. Solo hay que recompilarlo al cambiar
`tunnel/`, con Go y el NDK de Android (gomobile lo instala el script, de la versión de `go.mod`):

```sh
cd tunnel && go test ./...        # el túnel contra un "router" WireGuard en memoria
sh tools/build-tunnel.sh          # vuelve a generar app/libs/wrtcvpn.aar (arm64, arm y x86_64)
```

El `.aar` es reproducible: con las versiones fijadas en el script (Go, gomobile, NDK y JDK) sale
igual en Windows y en Linux, sin rutas ni nombres de la máquina. El trabajo `tunnel-aar` de la CI
lo compila y falla si no coincide con el del repositorio, así que el binario que se sube es
siempre el que sale de `tunnel/`. Si no coincide (otro NDK, por ejemplo), `sh tools/fetch-tunnel-aar.sh`
trae el que compiló la CI.

La versión debug sugiere `192.168.56.2`, que es la VM de OpenWrt en VirtualBox (red host-only).
El emulador de Android llega a esa red a través del PC.

### Pruebas de capturas

`ScreenshotTest` pinta los componentes y una pantalla con datos de ejemplo, en claro y en oscuro,
y los compara con las imágenes de `app/src/test/screenshots` (Roborazzi sobre Robolectric: corre en
la JVM, sin emulador). Si el aspecto cambia sin querer, la CI falla.

```sh
./gradlew :app:verifyRoborazziDebug   # comparar; es lo que hace la CI
./gradlew :app:recordRoborazziDebug   # guardar las nuevas tras un cambio de aspecto buscado
```

Las diferencias quedan en `app/build/outputs/roborazzi`. Se tolera hasta un 1 % de la imagen,
porque Windows y Linux no suavizan las letras exactamente igual.

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

El cliente (MAC `02:00:00:00:00:99`) desaparece al reiniciar el router, y reiniciar la red lo
saca de `br-lan` (las pruebas lo vuelven a meter). La prueba deja el router sin reglas de
WrtControl y en UTC.

`AddonsVmTest` prueba los paquetes contra la misma VM:
- Desinstala WireGuard y lo instala la app (6–8 s, con el reinicio de la red).
- Activa SQM en la WAN y comprueba con `tc` que `cake` queda puesto.
- Abre un puerto por UPnP desde el cliente con `upnpc` (instala `miniupnpc` en la VM) y la app
  lo ve y lo cierra. Comprueba también que, si miniupnpd no arranca, el router lo deshace solo.
- Da acceso remoto y conecta con un WireGuard de verdad desde el cliente: llega a la web del
  router por el túnel, pero no a SSH ni a internet. Comprueba que el control parental no toca
  las secciones del acceso remoto.
- DNS dinámico con un "proveedor" de prueba en el PC (la VM llega por `10.0.3.2`): ddns-scripts
  le envía el nombre, la IP y el token.

`DeviceReportVmTest` hace el informe del router (solo lee) y comprueba que no lleva sus MAC ni sus
direcciones de la WAN; lo deja en `app/build/device-report-vm.json` para mirarlo.

`HomeNetworkVmTest` renombra la red de casa (`lan` → `casa`: interfaz, zona, reglas, DHCP y Wi-Fi)
y comprueba que la app la encuentra, identifica el mismo router y sigue viendo al cliente de
pruebas; al final deja `/etc/config` como estaba. Solo corre en una máquina virtual (QEMU o
VirtualBox, por `/sys/class/dmi/id/sys_vendor`): en un router de casa cortaría la red un momento.
Las órdenes van por la entrada de `ssh … sh -s`, porque desde Windows `ProcessBuilder` reescribe
las comillas de los argumentos.

En Android las expresiones regulares las compila ICU, no la JVM de las pruebas: ICU lee `[:` como
el principio de una clase POSIX y la app se cierra al cargar la clase. `IcuRegexTest` revisa que
ninguna `Regex("…")` lo lleve (se escribe `[-:]`, no `[:-]`).

### Prueba contra un router de verdad

Las mismas pruebas valen para un router físico (probadas en un GL.iNet GL-MT6000 con 25.12.6). El
cliente de pruebas toma la LAN del router (la `.99`) y las direcciones se leen del router. Lo que
cambia respecto a la VM:
- El PC no siempre queda al otro lado de la WAN, así que el eco de las conexiones largas va en
  otro namespace del router, dentro de la zona wan (`inet-up`, necesita `socat`).
- Si el PC sale a internet por ese router, la cuarentena lo dejaría sin conexión:
  `WRTCONTROL_KEEP_MACS` lo aprueba siempre.
- El "proveedor" de DNS dinámico va también en ese "internet" (`WRTCONTROL_DDNS_TARGET`), sin abrir
  el firewall del PC. También puede ir en el PC, que el router ve por la LAN (`WRTCONTROL_PC_ADDRESS`,
  y `WRTCONTROL_PC_PORT` para fijar el puerto que se abre en su firewall). La IP que el nombre
  tiene "registrada" la da dnsmasq del router, sin depender del DNS del módem.
- Con contraseña de root, las pruebas la leen de `WRTCONTROL_PASSWORD`, y el SSH entra con una clave
  (`WRTCONTROL_SSH_OPTS=-i ~/.ssh/clave -o IdentitiesOnly=yes`).
- A distancia, por un WireGuard propio del router: si el router tiene un túnel WireGuard que no es
  el de la app, la prueba que desinstala WireGuard se salta (dejaría fuera a quien la lanza).
- Las pruebas dejan cada función como al instalar el paquete. Si el router ya usa el DNS dinámico,
  el acceso remoto o UPnP de la app, esas pruebas se saltan para no borrar la configuración de verdad.

```sh
ssh root@192.168.8.1 'apk add ip-full kmod-veth socat miniupnpc'
ssh root@192.168.8.1 'cat > /tmp/c.sh && sh /tmp/c.sh up && sh /tmp/c.sh inet-up' < tools/vm/netns-client.sh
set WRTCONTROL_VM=192.168.8.1
set WRTCONTROL_KEEP_MACS=AA:BB:CC:DD:EE:FF
set WRTCONTROL_ECHO_TARGET=198.51.100.2 47101
set WRTCONTROL_DDNS_TARGET=198.51.100.2:47102
gradlew testDebugUnitTest --tests "*VmIntegrationTest*" --tests "*AddonsVmTest*" --rerun
ssh root@192.168.8.1 'sh /tmp/c.sh inet-down; sh /tmp/c.sh down'
```

**En la CI** (`.github/workflows/vm.yml`) las dos pruebas se ejecutan cada noche contra 24.10, 25.12
y snapshot (la última versión de cada serie), y en los PR que tocan `parental/`, `addons/`,
`openwrt/`, `ubus/` o estas pruebas. `tools/vm/openwrt-qemu.sh` hace la imagen con el Image
Builder oficial (con la clave SSH y los paquetes ya puestos) y la arranca en QEMU con la misma red
que la VM de VirtualBox, así que las pruebas no cambian. Vale en cualquier Linux con KVM:

```sh
sh tools/vm/openwrt-qemu.sh build 25.12 vm ~/.ssh/id_ed25519.pub
sh tools/vm/openwrt-qemu.sh up vm/openwrt.img      # LAN por tap (sudo): el router queda en 192.168.56.2
```

### El router de prueba del README

`tools/vm/demo.sh` y `tools/vm/demo.ps1` (la guía "Probarla sin router" del README) son para quien
llega sin router, no para las pruebas: la imagen oficial sin tocar (sin clave SSH ni paquetes de
más), red de usuario de QEMU (sin sudo ni tap) y la web del router en el puerto 8080 del PC. La
primera vez arranca solo para el PC (`127.0.0.1`), pone a `root` una contraseña aleatoria con
`luci setPassword` y apaga; desde entonces escucha en toda la red del PC, para el teléfono.
Guarda la imagen y la contraseña en `~/.cache/wrtcontrol-demo` (`%LOCALAPPDATA%\wrtcontrol-demo`
en Windows). El trabajo `demo` de `vm.yml` arranca `demo.sh`, lo apaga, lo vuelve a arrancar y pasa
`DemoRouterTest` (la app entra y lee lo de la primera pantalla).

Si en la VM está el `nft` de OpenWrt 22.03 en `/tmp/nft102` (paquetes `nftables-json`,
`libnftnl11`, `libmnl0` y `jansson4` de 22.03.7 para x86_64), la prueba pasa también el fichero
de reglas por su analizador. Esa copia se cuelga al hablar con el kernel de 25.12, pero analiza
bien: basta con que llegue al final del fichero sin errores.

## Publicar una versión

1. En un PR: subir `wrtc.version` en `gradle.properties` (de ahí sale también `versionCode`:
   1.4.12 → 10412) y pasar lo de "Sin publicar" de `CHANGELOG.md` a una sección `## [X.Y.Z]`.
2. Fusionado, etiquetar ese commit: `git tag vX.Y.Z && git push origin vX.Y.Z`.
3. El flujo `release.yml` comprueba que la etiqueta, la versión y el CHANGELOG coinciden, pasa
   las pruebas, compila la release firmada y crea la release de GitHub con el APK y sus notas.

La clave de firma no va en el repositorio. La CI la lee de los secrets `WRTC_KEYSTORE_BASE64`,
`WRTC_KEYSTORE_PASSWORD`, `WRTC_KEY_ALIAS` y `WRTC_KEY_PASSWORD`. Para compilar una release
firmada en el PC, las mismas en variables de entorno, con `WRTC_KEYSTORE` como ruta al `.jks`, o en
`~/.gradle/gradle.properties` como `wrtc.keystore=…`, `wrtc.keystore.password=…`, etc. Sin clave,
`assembleRelease` la deja sin firmar. Si se pierde la clave, las versiones nuevas no se pueden
instalar encima de las anteriores: guárdala con una copia fuera del PC.

Cada PR, pequeño y con su definición de hecho (la plantilla de PR): pruebas, CI en verde (incluidas
las VM si toca el router), README y ROADMAP al día, línea en el CHANGELOG y captura si cambia una
pantalla.
