# Cambios

Lo que cambia en cada versión publicada, para quien usa la app. Cada PR que cambia algo visible
añade una línea en "Sin publicar"; al publicar, esa sección pasa a ser la de la versión (ver
"Publicar una versión" en docs/desarrollo.md).

## [Sin publicar]

### Añadido
- Uso de internet (en Más): cuánto se ha descargado y subido desde que se encendió el router, el
  total y la media al día. Sale de los contadores del router: no hace falta instalar nada.

## [0.4.0]

### Añadido
- Probar la app sin router: `tools/vm/demo.sh` (Linux, macOS) y `tools/vm/demo.ps1` (Windows)
  arrancan un OpenWrt de prueba en el ordenador con un comando (ver "Probarla sin router" en el
  README).
- Router (en Más): "Compartir informe" prepara un archivo con cómo es el router y cómo está
  configurado, para que la app se pruebe en más routers. Antes de salir del teléfono se quitan
  las MAC, las IP públicas, los nombres, las claves y las redes, y no lleva los aparatos de casa.
- Icono propio de la app: un escudo con las ondas del Wi-Fi, también en versión monocroma para los
  iconos temáticos de Android 13 o más.
- Wi-Fi (en Más): nombre, contraseña, seguridad (WPA2/WPA3 por defecto) y qué bandas emiten,
  con el mismo nombre en las dos. Antes de un cambio que saca a alguien del Wi-Fi, la app dice
  cuántos aparatos se desconectarán. Avisa si la red no tiene contraseña o si el Wi-Fi del
  router está apagado (y que puede ser a propósito, si lo dan otros aparatos).
- Wi-Fi: el país. Si el router no lo tiene puesto (así viene de fábrica, y entonces usa los
  canales y la potencia más limitados), la app lo avisa y propone el del teléfono. Si el Wi-Fi no
  vuelve a emitir con el país nuevo, el router lo deja como estaba.
- Wi-Fi: velocidad y alcance. En cada banda se elige entre lo recomendado (más alcance y menos
  cortes) y lo más rápido, sin hablar de MHz. La máxima de 5 GHz avisa de que el router escucha
  un minuto por si hay radares antes de emitir, y pide tener puesto el país.
- Wi-Fi: el canal más libre. El router mira qué canales usan las redes de alrededor y la app
  propone el que menos vecinos tiene, si de verdad está más libre que el actual.
- Wi-Fi de invitados (en Más): una red aparte para las visitas, con código QR para conectarse.
  Tienen internet, pero no ven tus aparatos ni pueden entrar al router. Al apagarlo se conservan
  el nombre y la contraseña para la próxima vez.
- Wi-Fi: potencia (toda, media o baja) y "Conectados ahora": quién hay en cada banda y si su
  señal es muy buena, buena, justa o débil.
- Inicio: dos tarjetas nuevas llevan al Wi-Fi (su nombre y cuántos hay conectados) y al Wi-Fi de
  invitados. Si el router no emite pero tu teléfono está por Wi-Fi en casa, dice que el Wi-Fi lo
  dan otros aparatos (repetidores, Deco) en vez de avisar de que está apagado.
- Puesta en marcha: si el Wi-Fi del router no tiene país, un paso nuevo lo pide y propone el del
  teléfono.
- Wi-Fi: al cambiar el nombre o la contraseña, "Guardar en este teléfono" guarda la red nueva con
  un toque (Android 11 o más), sin buscarla en Ajustes.
- Wi-Fi de invitados: "Borrar el Wi-Fi de invitados" lo quita del todo del router (apagarlo
  conserva el nombre y la contraseña para la próxima vez).
- Wi-Fi: horario. Apaga el Wi-Fi del router (también el de invitados) a las horas que elijas,
  por ejemplo de 23:00 a 07:00 de domingo a jueves, y lo vuelve a encender solo. Dentro del
  horario, "Encender ahora" lo enciende hasta la próxima hora de apagar. Si se va la luz o
  reinicias el router, el Wi-Fi vuelve a encenderse.
- Puesta en marcha: la primera vez que entras en un router, la app te lo presenta en cuatro pasos:
  qué router es, si está protegido con contraseña (y ponerla si no), qué puede hacer y qué va a
  cambiar en él. Se puede saltar, y no se repite.
- Al conectar, la app propone la dirección del router del Wi-Fi en el que estás.
- Añadir otro teléfono (en Más): un código QR que el otro teléfono escanea con su cámara para
  encontrar el router. La contraseña no va en el código: se escribe en el teléfono nuevo.
- "Esto es lo que va a cambiar": antes de un cambio con consecuencias (bloquear un aparato, fijar
  su dirección, cambiar de quién es, la zona horaria o la contraseña del router), la app enseña
  qué cambia y qué supone, y espera a que lo aceptes.
- Dispositivos: tocando un aparato se le pone nombre ("Tele de la sala"), se le fija la dirección
  y se dice de quién es. Se guarda en el router, así que lo ven todos los teléfonos con la app.
- Dispositivos: cortar internet a un aparato suelto, un rato (30 minutos, 1 o 2 horas) o hasta
  que se lo devuelvas, esté o no en el perfil de una persona. Mientras haya algún aparato así,
  los teléfonos con una versión anterior de la app solo pueden mirar: hay que actualizarlos.
  (Tras vencer una pausa, siguen así hasta el siguiente cambio hecho con la versión nueva.)
- Dispositivos: a los aparatos que no dicen su nombre, el router se lo pregunta (mDNS). Algunas
  teles, altavoces e impresoras pasan así de "Aparato de tal marca" a su nombre o su modelo.
- Dispositivos: cada aparato dice si está en línea, sin actividad o desconectado. Los que tienen
  nombre o dirección guardados siguen en la lista aunque no estén.

### Cambiado
- Router (en Más): cada función dice si funciona en este router (la app la activó y la comprobó,
  o la ve en marcha) o si está sin probar aquí, y entonces si la app está probada con su versión
  de OpenWrt.
- Routers con la red de casa con otro nombre (no `lan`): la app la encuentra por el cortafuegos.
  Dispositivos, "Tu Wi-Fi" y saber qué router es funcionan igual que con la de serie.
- Los cambios en el router se ven igual en toda la app: qué se está haciendo y por qué paso va
  (aplicar, comprobar, confirmar). Si un cambio no funciona, la app dice que el router lo está
  deshaciendo en vez de quedarse esperando sin explicar nada.
- Familia y Seguridad llaman a cada aparato igual que Dispositivos: con el nombre que le pusiste,
  el suyo o su fabricante ("Aparato Samsung"), en vez de "Sin nombre" o la MAC.

### Corregido
- En un router sin Wi-Fi (un PC con OpenWrt, el router de prueba), "Tu Wi-Fi" y el Wi-Fi de
  invitados dicen que no tiene, en vez de un error que se repetía sin parar.
- En inglés, el atajo "Every day" del diálogo de horarios ya no se escribe en vertical.
- UPnP: en OpenWrt 24.10, si miniupnpd se caía nada más arrancar, la app podía darlo por
  activado al preguntar justo cuando el router lo relanzaba. Ahora tiene que seguir en marcha
  unos segundos; si no, el router lo deshace y la app lo dice.
- Acceso remoto: al instalar WireGuard, si el router tardaba en contestar justo después de
  instalar el paquete (puede quedarse hasta medio minuto sin atender), la app daba la instalación
  por fallida y no reiniciaba la red. Ahora espera a que vuelva y termina.

## [0.3.0]

La app cambia de cara y de orden: empieza por cómo está la red, no por los datos técnicos.

### Cambiado
- Cara nueva: colores y tipografía propios (azul cobalto y Manrope), iguales en todos los
  teléfonos, en claro y en oscuro. El verde, el ámbar y el rojo quedan para decir cómo está algo.
- Navegación nueva: Inicio · Dispositivos · Familia · Seguridad · Más. Los paquetes pasan a
  "Más → Funciones del router" y los datos técnicos a "Más → Router". Desconectar también está en Más.
- La app, en inglés si el teléfono está en inglés: pantallas, mensajes de error, consejos del
  acceso remoto, horarios, filtros y descripciones de los paquetes.
- Al cambiar de pestaña se ve al momento lo último que se sabía del router, en vez de esperar a
  que vuelva a cargar. La app le hace menos llamadas: cada dato se pide una sola vez aunque lo
  usen varias pantallas.

### Añadido
- Inicio: si el router tiene internet y desde cuándo, cuántos dispositivos hay, los perfiles en
  pausa y lo que pide atención (router sin contraseña, reglas sin aplicar, dispositivos nuevos).
- Salud del cable de internet: Inicio avisa si el cable entre el módem y el router se desconecta
  a ratos o va más lento de lo que admiten los dos aparatos, y dice qué hacer. "Conectado a
  internet" se comprueba desde el router (un ping y, si no sale, una consulta de DNS), y si falla
  distingue el cable suelto de un problema del módem o del proveedor.
- Dispositivos: cada aparato con su icono y su fabricante. Los que no dicen su nombre salen como
  "Aparato Samsung" en vez de "Sin nombre", y se avisa de los que usan una MAC privada. El
  fabricante se saca de una lista que va dentro de la app: las MAC no salen del teléfono.
- "Qué puede hacer este router", en Más → Router: cada función de la app aparece como disponible,
  se instalará o no compatible, con el motivo y qué hacer. También dice qué Wi-Fi tiene el router.

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

[Sin publicar]: https://github.com/2hotluigi/WrtControl/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/2hotluigi/WrtControl/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/2hotluigi/WrtControl/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/2hotluigi/WrtControl/releases/tag/v0.2.0
