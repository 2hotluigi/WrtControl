# Diseño

El lenguaje visual de WrtControl (desde la 0.6.0): listas agrupadas sobre un fondo neutro, cifras
grandes como protagonistas y controles que se entienden sin leer. Se parece a lo que hacen las apps
de routers "premium" (Firewalla, Deco), con nuestros colores, nuestros iconos (Material Icons) y
nuestra fuente (Manrope).

La maqueta de referencia son las capturas `propuesta-*` de `app/src/test/screenshots`
(`ProposalScreenshotTest`): Inicio, Dispositivos y la ficha de un dispositivo, en claro y oscuro.

## Colores

La paleta está en `ui/design/Palette.kt` y la pone `WrtTheme`. El esquema de Material sale de ella,
así que los componentes de Material se ven con los mismos colores.

| Papel | Oscuro | Claro |
|---|---|---|
| Fondo | `#000000` | `#F2F2F7` |
| Tarjeta | `#1C1C1E` | `#FFFFFF` |
| Sobre la tarjeta (botón redondo, carril de pestañas) | `#2C2C2E` | `#EDEDF2` |
| Separador (1/2 dp) | `#38383A` | `#E3E3E8` |
| Texto / secundario / terciario | `#FFFFFF` / `#98989F` / `#636366` | `#111114` / `#6C6C72` / `#AEAEB2` |
| Acento (marca, lo que se toca) | `#3D7BFF` | `#2350E6` |
| Bien / aviso / problema | `#30D158` / `#FF9F0A` / `#FF453A` | `#1FA855` / `#E07F00` / `#E5342B` |

Verde, naranja y rojo solo dicen cómo está algo, y siempre van con texto o icono. Las series de las
gráficas (recibido y enviado) tienen sus propios colores, comprobados para daltonismo (`ChartColors`).

## Componentes (`ui/design`)

- `GroupCard`: una tarjeta de esquinas grandes (18 dp), sin sombra ni borde. Separación entre
  tarjetas, `CardGap` (14 dp); margen de la pantalla, 16 dp.
- `GroupRow`: etiqueta a la izquierda, valor a la derecha y `›` si lleva a otro sitio; la línea fina
  de debajo empieza donde el texto. Con `leading` (`RowIcon`: icono de color sobre un cuadrado
  redondeado, con un punto de estado opcional) y `supporting` (segunda línea gris).
- `SwitchRow`, `ActionRow` (acción sola, centrada; roja si no tiene vuelta atrás), `SectionHeader`
  (título pequeño en mayúsculas encima de un grupo) y `GroupFooter` (explicación debajo).
- `StatStrip`: dos o tres cifras grandes con su etiqueta encima, separadas por líneas verticales.
- `PillTabs`: pestañas en píldora sobre un carril gris, sin marcas.
- `CircleAction`: control redondo con su nombre y cómo está ("Pausado · hasta 18:30"); el activo,
  de color.
- `FeatureTile`: icono de color y nombre, para la cuadrícula de funciones.
- `CenterTopBar` (título centrado) y `FloatingNavBar` (píldora flotante con las pestañas y un botón
  redondo aparte).

## Reglas

- Poco texto: una cifra o un estado antes que una frase. La explicación, en `GroupFooter` o en la
  pantalla de detalle, no en la tarjeta.
- Una ficha es una pantalla, no un diálogo.
- Cada pantalla nueva o cambiada lleva sus capturas en claro y oscuro.
