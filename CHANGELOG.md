# Cambios

Lo que cambia en cada versión publicada, para quien usa la app. Cada PR que cambia algo visible
añade una línea en "Sin publicar"; al publicar, esa sección pasa a ser la de la versión (ver
"Publicar una versión" en el README).

## [Sin publicar]

### Cambiado
- La app, en inglés si el teléfono está en inglés: pantallas, mensajes de error, consejos del
  acceso remoto, horarios, filtros y descripciones de los paquetes.
- Al cambiar de pestaña se ve al momento lo último que se sabía del router, en vez de esperar a
  que vuelva a cargar. La app le hace menos llamadas: cada dato se pide una sola vez aunque lo
  usen varias pantallas.

- Cara nueva: colores y tipografía propios (azul cobalto y Manrope), iguales en todos los
  teléfonos, en claro y en oscuro. El verde, el ámbar y el rojo quedan para decir cómo está algo.

### Añadido
- "Qué puede hacer este router", en Estado: cada función de la app aparece como disponible, se
  instalará o no compatible, con el motivo y qué hacer. También dice qué Wi-Fi tiene el router.

## [0.2.0]

Primera versión publicada.

### Control parental
- Perfiles por persona con sus dispositivos: pausa (con o sin fin), horarios sin internet y
  tiempo extra.
- Filtro de contenido por perfil: niveles para familias, servicios y sitios bloqueados, búsqueda
  segura y YouTube restringido.
- Protección: dispositivos nuevos sin internet hasta aprobarlos, DNS cifrado bloqueado, corte de
  conexiones abiertas al endurecer el filtro y perfiles sin acceso al router.
- Todo cambio se aplica con vuelta atrás automática si el router no lo carga, y queda una copia
  de los perfiles en el teléfono.
- Varias versiones de la app en el mismo router: se conserva lo que una versión no conoce, y una
  versión anterior no escribe sobre una más nueva.

### Paquetes y acceso remoto
- Instala y configura WireGuard, DNS dinámico, SQM y UPnP desde la app.
- Acceso remoto con un WireGuard propio dentro de la app, sin usar la VPN de Android.
- "¿Se llega desde fuera?": prueba por los datos móviles sin salir del Wi-Fi y explica qué falla
  (cortafuegos del módem, doble NAT, datos sin IPv6).
- El DNS dinámico borra en el proveedor la dirección de la familia que no publica.

### Android y la app
- Android 17: pide el permiso de red local antes de conectar y explica para qué.
- Errores en palabras: qué pasó y qué hacer, sin nombres de clases ni códigos.
- Bloqueo con huella o PIN.
- Avisa si el router tiene una versión de OpenWrt anterior a 24.10.

[Sin publicar]: https://github.com/2hotluigi/WrtControl/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/2hotluigi/WrtControl/releases/tag/v0.2.0
