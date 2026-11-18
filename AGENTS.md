# Instrucciones para agentes

Convenciones de WrtControl para agentes de IA (Claude Code, Codex y similares) y para cualquiera
que llegue nuevo. Lo que se cuenta aquí no se repite en otros sitios: el resto está en
[docs/desarrollo.md](docs/desarrollo.md) (compilar y probar), [docs/como-funciona.md](docs/como-funciona.md)
(qué hace la app en el router) y [ROADMAP.md](ROADMAP.md) (qué hay hecho y qué sigue).

## El proyecto

App Android (Kotlin, Jetpack Compose, Material 3) que administra routers OpenWrt 24.10+ por ubus
JSON-RPC, el mismo endpoint que usa LuCI. El túnel WireGuard del acceso remoto es Go (`tunnel/`) y
va ya compilado en `app/libs/wrtcvpn.aar`.

Código, comentarios, commits, PR y documentación técnica: **en español**. La interfaz, en español
(`values/`) e inglés (`values-en/`). El README tiene versión en inglés (`README.md`) y en español
(`README.es.md`): un cambio en uno va también en el otro.

## Principios que ningún cambio puede romper

1. **Nunca dejar al usuario sin internet ni sin router.** Todo lo que escribe en el router pasa por
   `applySafely` (`parental/SafeApply.kt`): `uci apply` con reversión, comprobar en el router que
   funciona y solo entonces confirmar. Si el cambio puede sacar al teléfono de la red, más plazo.
2. **Empezar sin instalar nada.** Lo básico tiene que funcionar con el ACL de LuCI. Antes de usar un
   objeto de ubus o un fichero, comprobar que ese ACL lo deja (las ACL están en
   `/usr/share/rpcd/acl.d/` del router).
3. **Nada escrito a mano sobre un router concreto.** Interfaces, radios, zonas y paquetes se leen del
   router. Lo que la app crea lleva el prefijo `wrtc` y se conserva lo que no es suyo.
4. **Lenguaje llano.** Cada opción dice qué consigue. Los textos van en `strings.xml` (nunca fijos en
   una pantalla: `HardcodedTextTest` lo vigila); la lógica devuelve `Txt`. Los errores pasan por
   `describeError`: sin nombres de clase ni códigos.
5. **Privacidad.** Ni telemetría ni servicios de terceros. Nada de MAC, IP públicas, nombres, claves
   ni contraseñas en el código, las pruebas, las capturas, los commits o los PR.

## Antes de dar algo por hecho

```sh
./gradlew :app:testDebugUnitTest :app:verifyRoborazziDebug :app:detekt :app:lintDebug
```

- Si cambia el aspecto a propósito: `./gradlew :app:recordRoborazziDebug` y mirar las imágenes
  (`app/src/test/screenshots/` y las del README en `docs/screenshots/`).
- detekt: imports en orden lexicográfico (java, javax y kotlin al final), sin números mágicos
  (constantes con nombre), funciones y ficheros sin pasar de los límites de `config/detekt`.
- En `values-en/` el apóstrofo va escapado (`\'`).
- Lo que toca `parental/`, `addons/`, `openwrt/` o `ubus/` lleva prueba de VM (`*VmTest`), que la CI
  pasa contra OpenWrt 24.10, 25.12 y snapshot en QEMU.
- **No lanzar pruebas contra un router físico sin que lo pida su dueño**: recargan el cortafuegos
  y la red de una casa real.

## Forma de trabajar

- PR pequeños, de un solo tema, con la definición de hecho de la plantilla: pruebas, CI en verde,
  README/docs y ROADMAP al día, línea en `CHANGELOG.md` ("Sin publicar") y captura si cambia una
  pantalla.
- El mantenedor revisa y fusiona. No subir a `master` directamente.
- Cada tarea del ROADMAP se tacha (`~~…~~`) con una línea de qué se hizo y cómo se probó.
