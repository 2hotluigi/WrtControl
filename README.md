# WrtControl

App Android (Kotlin + Jetpack Compose) para administrar routers **OpenWrt 22.03 o superior**
desde el celular: estado, dispositivos y, en las siguientes fases, control parental, horarios
y acceso remoto por WireGuard.

Habla con el router por **ubus JSON-RPC** (`/ubus`, el mismo endpoint que usa LuCI), así que no
hay que instalar nada en el router para la parte de lectura.

## Compilar y probar

```sh
gradlew testDebugUnitTest   # pruebas unitarias (JVM, sin emulador)
gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

La versión debug sugiere `192.168.56.2`, que es la VM de OpenWrt en VirtualBox (red host-only).
El emulador de Android llega a esa red a través del PC.

## Estructura

| Paquete | Qué hace |
|---|---|
| `ubus` | Cliente JSON-RPC. Distingue sesión caducada de permiso denegado (`-32002` es ambiguo y se resuelve con `session.access`). Renueva la sesión automáticamente, con un solo login aunque haya llamadas concurrentes. |
| `openwrt` | Modelos y llamadas tipadas (`system`, `network.interface`, `luci-rpc`). `buildDeviceList` combina vecinos y concesiones DHCP, incluido el caso de Windows, que usa en DHCPv6 la MAC de otra tarjeta. |
| `data` | Router guardado en DataStore. La contraseña se guarda cifrada con AES-GCM y una clave del Android Keystore. |
| `ui` | Compose + Material 3. Los datos se recargan periódicamente y la recarga se detiene cuando la app pasa a segundo plano. |

## Hoja de ruta

1. ~~Base: conexión, estado y dispositivos~~
2. Control parental: perfiles por persona, horarios, pausar internet y filtrado por dominio
   (`uci apply` con reversión automática para no perder el acceso)
3. Acceso remoto: túnel WireGuard integrado en la app y configuración del router desde la app
4. Seguridad: usuario rpcd con ACL limitado (no root) y emparejamiento por QR
