# Plan de integración IPFS en StreamVault HaP

Documento de trabajo para retomar la tarea con otro agente. Describe **qué**
construir, **por qué** esa forma y no otra, y **en qué orden**. No hay código
aplicado todavía: este MD es el plan.

Estado: propuesta técnica, pendiente de aprobación del mantenedor en los puntos
marcados como **DECISIÓN**.

---

## 1. Objetivo

Añadir a HaP un apartado **IPFS** que para el usuario sea un simple interruptor:

- Primera vez: se prepara el nodo y arranca el daemon.
- Siguientes veces: al abrir la app / al arrancar HaP, solo se levanta el daemon.
- Con el nodo levantado, HaP puede reproducir contenido `ipfs://` / `ipns://`
  igual que hoy reproduce `acestream://`, reutilizando la misma tubería
  (`playback.prepare`, `cast.rewriteUrl`, listas M3U).

---

## 2. Revisión de la idea original (Termux) — qué funciona y qué no

La idea de partida era: descargar Termux desde sus releases de GitHub, instalar
IPFS dentro (`pkg install ipfs`), `ipfs init` y `ipfs daemon`.

Al validarla aparecen cuatro bloqueos, tres de ellos duros:

| # | Bloqueo | Gravedad |
|---|---------|----------|
| 1 | El paquete **no se llama `ipfs`** sino **`kubo`** (`packages/kubo` en termux-packages). `pkg install ipfs` falla. | Menor: se corrige el comando |
| 2 | Instalar el APK de Termux requiere `REQUEST_INSTALL_PACKAGES` **y** que el usuario acepte el instalador del sistema, pantalla a pantalla. | Duro: rompe el "un solo check" |
| 3 | Ejecutar comandos dentro de Termux desde otra app requiere el intent `RUN_COMMAND`, el permiso `com.termux.permission.RUN_COMMAND` **y** que el usuario edite a mano `~/.termux/termux.properties` poniendo `allow-external-apps=true` y reinicie Termux. **Esto no es automatizable**: es el interruptor de seguridad de Termux y se comprueba aunque el permiso esté concedido. | Duro: rompe el "un solo check" |
| 4 | Termux en Android TV / Fire TV (target real de este plugin, hay `LEANBACK_LAUNCHER` en el manifest) es casi inusable sin teclado y ratón. | Duro en TV |

Conclusión: **la vía Termux no puede dar la experiencia de un check**. Como
mínimo exige tres intervenciones manuales del usuario fuera de nuestra app, una
de ellas editando un fichero de texto en una terminal.

Referencias:
- <https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent>
- <https://github.com/termux/termux-packages/tree/master/packages/kubo>

---

## 3. Alternativa recomendada: kubo embebido como librería nativa

**El repo ya resuelve este problema exacto.** `AceServeRuntime` ejecuta Python
lanzando `nativeLibraryDir/libacepython.so` con `ProcessBuilder`. Es el patrón
estándar en Android para ejecutar un binario propio, y es obligatorio:

> Desde Android 10 (API 29) está prohibido ejecutar ficheros del directorio de
> datos de la app. El único sitio con permiso de ejecución es
> `ApplicationInfo.nativeLibraryDir`, que se rellena desde `jniLibs/<abi>/`.

Esto además descarta de raíz cualquier variante de "descargar el binario de
kubo en tiempo de ejecución": lo descargado a `filesDir` **no se puede
ejecutar**. O va en el APK, o va en otra app (Termux).

El proyecto ya tiene la pieza clave activada: `app/build.gradle.kts` fija
`packaging { jniLibs { useLegacyPackaging = true } }`, que garantiza que las
librerías se extraen al disco y por tanto son ejecutables.

### Diseño

- kubo se compila para `android/arm64` y `android/arm`, se renombra a
  `libipfs.so` y se coloca en `app/src/main/jniLibs/<abi>/`.
- `IpfsRuntime` (espejo de `AceServeRuntime`) hace `init` + `daemon` con
  `IPFS_PATH` apuntando a `filesDir/ipfs/<abi>`.
- El gateway HTTP local (`127.0.0.1:8080`) es lo que consume el reproductor.

Ventajas frente a Termux:

- Un solo check, sin salir de la app, sin instalar nada, sin editar ficheros.
- Funciona igual en móvil, tablet y Android TV.
- Primera ejecución rápida: no hay descarga de paquetes, solo `ipfs init` (~2 s).
- Versión de kubo controlada por nosotros, no por el repo de Termux.
- No dependemos de que Termux esté instalado, actualizado, ni de sus permisos.

Coste principal: **tamaño del APK**. Ver §8.

---

## 4. Modo avanzado: nodo IPFS externo (aquí encaja Termux)

No tiramos la idea de Termux, la reubicamos donde sí es honesta: como **modo
avanzado "usar un nodo IPFS externo"**, con campos host/puerto. Sirve para:

- Un Termux que el usuario ya tenga configurado a mano.
- Un nodo en un NAS, un PC o una Raspberry de la misma LAN.
- Depuración.

En ese modo HaP **no arranca nada**: solo apunta el gateway a
`http://<host>:<puerto>` y comprueba salud contra `/api/v0/id`. Nada de
`RUN_COMMAND`, nada de instalar APKs de terceros. Si en el futuro se quiere
automatizar Termux, se añadiría encima de este modo, con una guía en pantalla
de los pasos manuales.

**DECISIÓN 1:** confirmar que el modo externo se incluye desde la fase 1 o se
pospone a una fase posterior. Recomendación: incluirlo, cuesta poco (es solo
persistir host/puerto y saltarse el arranque) y da una vía de escape cuando el
binario embebido falle en un dispositivo raro.

---

## 5. Arquitectura y ficheros

Nomenclatura y estilo espejo de lo existente, para que el diff sea idiomático.

### 5.1 Ficheros nuevos

| Fichero | Papel | Espejo de |
|---|---|---|
| `app/src/main/java/com/jopsis/httpaceserveproxy/IpfsRuntime.java` | prepara repo, arranca/para el daemon, bombea logs | `AceServeRuntime` |
| `app/src/main/java/com/jopsis/httpaceserveproxy/IpfsSettings.java` | preferencias (activado, modo, puertos, LAN, cuota) | `ProxyExposure` |
| `app/src/main/java/com/jopsis/httpaceserveproxy/IpfsHealthClient.java` | sonda TCP + `POST /api/v0/id` | `HealthClient` |
| `app/src/main/jniLibs/<abi>/libipfs.so` | binario kubo (generado, **no versionado**) | `libacepython.so` |
| `tools/fetch-kubo.sh` | compila/descarga kubo para las dos ABIs en local | — |

### 5.2 Ficheros a modificar

| Fichero | Cambio |
|---|---|
| `ServiceState.java` | añadir `volatile boolean ipfsRunning` y `volatile String ipfsPhase` |
| `ProxySupervisorService.java` | acciones `ACTION_START_IPFS` / `ACTION_STOP_IPFS`; hilo supervisor propio `ipfs-supervisor`; arranque de IPFS si está activado; parada en `stopAll` |
| `HapBridge.java` | API pública IPFS + campos nuevos en `HapStatus` |
| `HapConfigActivity.java` | `buildIpfsPanel()` y su alta en `buildLayout()` |
| `StreamVaultHapPluginService.java` | resolución de `ipfs://` en `MSG_PREPARE_PLAYBACK` y `MSG_REWRITE_CAST_URL` |
| `res/values/strings.xml` y `res/values-es/strings.xml` | textos nuevos, **los dos a la vez** (regla de AGENTS.md) |
| `app/build.gradle.kts` | tarea `prepareIpfsBinaries`, opcionalmente `splits { abi }` |
| `.github/workflows/build-apk.yml` | paso de compilación de kubo antes de `assembleRelease` |
| `.gitignore` | ignorar `app/src/main/jniLibs/*/libipfs.so` |
| `README.md`, `docs/Changelog.md`, versión | regla de AGENTS.md: versión sincronizada en 4 sitios |

### 5.3 Puertos

Hay que evitar choques con lo que ya escucha HaP (proxy `8888`, AceServe `6878`
y `62062`):

| Servicio | Puerto propuesto | Nota |
|---|---|---|
| Gateway IPFS | **8080** | es el default de kubo; decisión explícita del mantenedor de mantenerlo en vez del 8081 propuesto aquí para evitar choques. Configurable por preferencia si algún dispositivo lo tiene ocupado. |
| API IPFS | **5001** | solo `127.0.0.1`, nunca expuesto a LAN |
| Swarm | **4001** | TCP+QUIC |

Definirlos como constantes en `IpfsSettings`, y el gateway configurable por
preferencia para poder moverlo si un dispositivo lo tiene ocupado.

---

## 6. Detalle de `IpfsRuntime`

Mismo contrato que `AceServeRuntime`: `prepare()`, `start()`, `stop()`,
`isRunning()`, todos `synchronized`.

### 6.1 Selección de ABI

Reutilizar la lógica de `AceServeRuntime.selectSupportedAbi()`: `arm64-v8a` si
`Process.is64Bit()`, si no `armeabi-v7a`, y `null` si no hay soporte (mensaje de
error claro, IPFS queda deshabilitado sin tumbar el resto de HaP).

**Importante:** *no* heredar la guarda `pageSize() > 4096` de AceServe. Esa
restricción es de las librerías nativas de Ace; el binario de kubo es Go
estático y funciona en imágenes de 16 KB.

### 6.2 `prepare()`

```java
File repo = new File(context.getFilesDir(), "ipfs/" + abi);   // IPFS_PATH
File marker = new File(repo, ".prepared-ipfs-v1");            // versionado, como Ace
```

Si no existe el marcador:

1. `libipfs.so init --profile=lowpower` con `IPFS_PATH` puesto.
   `lowpower` reduce la tabla de rutas y desactiva el reprovider: es el perfil
   correcto para un nodo que solo **consume** contenido.
2. Aplicar configuración con `libipfs.so config --json <clave> <valor>`
   (daemon parado):

   | Clave | Valor | Motivo |
   |---|---|---|
   | `Addresses.API` | `"/ip4/127.0.0.1/tcp/5001"` | API nunca fuera del dispositivo |
   | `Addresses.Gateway` | `"/ip4/127.0.0.1/tcp/8080"` o `0.0.0.0` si LAN | ver §7.3 |
   | `Routing.Type` | `"autoclient"` | no servimos DHT, solo consultamos |
   | `Swarm.ConnMgr.HighWater` | `40` | batería y datos |
   | `Swarm.ConnMgr.LowWater` | `20` | idem |
   | `Swarm.RelayService.Enabled` | `false` | no ser relay de terceros |
   | `Swarm.Transports.Network.Websocket` | `false` | superficie mínima |
   | `Datastore.StorageMax` | `"2GB"` (configurable) | cuota de disco |
   | `Datastore.GCPeriod` | `"1h"` | con `--enable-gc` |
   | `Discovery.MDNS.Enabled` | `true` | acelera contenido en la misma LAN |

3. Crear el marcador.

Siempre (haya marcador o no): reaplicar los valores que dependen de
preferencias del usuario (puerto de gateway, binding LAN, cuota), porque pueden
haber cambiado desde la última ejecución.

### 6.3 `start()`

```java
List<String> cmd = List.of(runner.getAbsolutePath(), "daemon", "--migrate=true", "--enable-gc");
ProcessBuilder b = new ProcessBuilder(cmd);
b.directory(repo);
b.redirectErrorStream(true);
b.environment().put("IPFS_PATH", repo.getAbsolutePath());
b.environment().put("HOME", repo.getAbsolutePath());
```

Hilo `ipfs-log` que vuelca a `ServiceState.appendLog("ipfs: " + line)`, calcado
de `AceServeRuntime.startLogThread()` (incluida la tolerancia a `IOException`
durante la parada).

Listo cuando responde `POST http://127.0.0.1:5001/api/v0/id` (la API de kubo es
POST, no GET) **y** el gateway acepta TCP. Timeout sugerido: 45 s en el primer
arranque (puede haber migración de repo), 20 s después.

### 6.4 `stop()`

`process.destroy()` (SIGTERM: kubo cierra el repo limpio), esperar 5 s,
`destroyForcibly()` si no. Antes de arrancar, si existe `repo.lock` y no hay
proceso vivo, borrarlo y dejar traza en el log.

### 6.5 Independencia del pipeline de Ace

**Regla de diseño:** un fallo de IPFS **nunca** debe poner HaP en `Failed` ni
impedir que AceServe/HTTPAceProxy arranquen, y viceversa. Por eso IPFS va en un
hilo supervisor propio dentro de `ProxySupervisorService`, con su propio estado
en `ServiceState` y su propio mensaje de error. El texto de la notificación
foreground puede mencionar IPFS, pero el estado global sigue mandándolo el
proxy.

---

## 7. Integración con el resto de HaP

### 7.1 `HapBridge`

```java
public static boolean isIpfsEnabled(Context c);
public static void setIpfsEnabled(Context c, boolean enabled);   // arranca o para
public static String ipfsGatewayUrl(Context c);                  // http://127.0.0.1:8080
public static String castIpfsGatewayUrl(Context c);              // IP LAN si procede
public static boolean isIpfsUrl(String url);
public static String resolveIpfsUrl(Context c, String url);      // ipfs://CID -> gateway
```

`HapStatus` gana `ipfsEnabled`, `ipfsRunning`, `ipfsPhase`, `ipfsError`,
`ipfsGatewayUrl`.

### 7.2 Reproducción

En `StreamVaultHapPluginService`:

- `MSG_PREPARE_PLAYBACK`: si la URL entra como `ipfs://<cid>[/ruta]`,
  `ipns://<nombre>` o `/ipfs/<cid>`, devolver
  `http://127.0.0.1:8080/ipfs/<cid>[/ruta]`. Si IPFS está desactivado o el
  daemon no está listo, devolver `handled=false` con mensaje, no una URL rota.
- `MSG_REWRITE_CAST_URL`: `127.0.0.1` → IP LAN, exactamente como hace
  `rewriteLocalUrlForLan` para el proxy. Requiere gateway en LAN (§7.3).

Las capabilities del manifest (`provider.m3u`, `playback.prepare`,
`cast.rewriteUrl`, `configuration.activity`) **no cambian**: IPFS entra por las
que ya existen. Esto respeta la regla de AGENTS.md de no tocar la superficie de
API anunciada.

### 7.3 Gateway en LAN

Mismo criterio que `ProxyExposure`: si el usuario activa "servidor LAN", el
gateway pasa a `0.0.0.0` para que Chromecast y reproductores externos lleguen.
Requiere además poner `API.HTTPHeaders.Access-Control-Allow-Origin` y
`Gateway.PublicGateways` con cuidado. **La API (5001) se queda siempre en
127.0.0.1**: exponerla en LAN da control total del nodo a cualquiera de la red.

**DECISIÓN 2:** ¿el gateway en LAN se ata al mismo interruptor "servidor LAN"
existente, o lleva el suyo propio? Recomendación: atarlo al existente, un
interruptor menos en pantalla y es la misma intención del usuario.

### 7.4 Listas M3U con entradas IPFS

`SourceValidator` hoy exige, según AGENTS.md, "al menos un target tipo
AceStream". Una lista solo-IPFS sería rechazada.

**DECISIÓN 3:** relajar la validación para aceptar también targets `ipfs://` /
`ipns://` como válidos. Es un cambio de semántica de validación, que AGENTS.md
marca como sensible, por eso lo dejo como decisión explícita y **fuera de la
fase 1**. Si se aprueba, va en fase 4 con sus propias pruebas.

---

## 8. Tamaño del APK: el coste real

kubo compilado ronda **90–110 MB por arquitectura** sin optimizar. Con
`-ldflags "-s -w"` baja a ~65–80 MB. Dos ABIs en un APK universal serían
+130–160 MB sobre un APK que ya pesa bastante (los zips de AceServe suman ~90 MB).

Mitigaciones, por orden de recomendación:

1. **Splits por ABI** en `build.gradle.kts` y subir dos APKs a la release. El
   usuario descarga solo lo suyo. Es el cambio de mayor impacto y el más simple.
   ```kotlin
   splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a"); isUniversalApk = false } }
   ```
   Ojo: cambia los nombres de los artefactos y hay que ajustar el workflow y las
   instrucciones de instalación del README.
2. `-ldflags "-s -w"` y `-trimpath` al compilar kubo. Gratis.
3. **DECISIÓN 4:** ¿solo `arm64-v8a` para IPFS? Casi todo el parque Android TV y
   móvil actual es arm64. Se ahorraría un binario entero y en `armeabi-v7a` el
   apartado IPFS se mostraría deshabilitado con un mensaje. Recomendación: sí,
   arm64 primero; añadir arm32 solo si alguien lo pide.

**No versionar el binario en git.** Los zips de Ace ya están en el repo por
historia, pero añadir 80 MB más por commit de actualización es insostenible. En
su lugar: `.gitignore` + `tools/fetch-kubo.sh` para local + paso de compilación
en CI.

### 8.1 Compilación de kubo

```sh
KUBO_VERSION=v0.43.0   # fijar versión, no "latest"
git clone --depth 1 -b "$KUBO_VERSION" https://github.com/ipfs/kubo
cd kubo
CGO_ENABLED=0 GOOS=android GOARCH=arm64 \
  go build -trimpath -ldflags "-s -w" -o libipfs.so ./cmd/ipfs
```

`CGO_ENABLED=0` es correcto: la única dependencia de cgo en kubo es el
transporte OpenSSL, que es opcional. El binario resultante es estático y no
depende de bionic.

En `.github/workflows/build-apk.yml`, añadir `actions/setup-go` y este paso
antes de leer la versión del APK, cacheando por `KUBO_VERSION` para no
recompilar en cada build.

---

## 9. UI: el apartado "IPFS"

Panel nuevo en `HapConfigActivity`, construido en código como todos los demás
(no hay layouts XML en este proyecto), insertado en `buildLayout()` justo
después de `buildExternalPlayersPanel()`.

Contenido:

- **Switch maestro "IPFS"** + subtítulo explicativo. Es el "simple check" del
  requisito.
- **Píldora de estado**: `Detenido` / `Preparando…` / `Iniciando…` / `En línea`,
  con los mismos colores que las píldoras existentes
  (`COLOR_SUCCESS`, `COLOR_WARNING`, `COLOR_ERROR`).
- **URL del gateway** con botón `Copy`, igual que las filas de `/aio`.
- **Último error** de IPFS, solo visible si lo hay.
- **Avanzado** (plegado): modo `Embebido` / `Nodo externo` (host + puerto),
  puerto del gateway, cuota de disco.

Reglas a respetar:

- El switch debe entrar en `busyControls` y respetar `suppressSwitchCallbacks`,
  como `externalServerSwitch` y `lanSwitch`.
- El refresco de estado va por `refreshRuntimeState()` (ya corre cada segundo).
- Todo el texto a `strings.xml`, **en `values/` y `values-es/` a la vez**.

Textos nuevos previstos (nombres siguiendo la convención existente):
`section_ipfs`, `label_ipfs_enable`, `message_ipfs_detail`,
`label_ipfs_gateway_url`, `status_ipfs_preparing`, `status_ipfs_starting`,
`status_ipfs_online`, `status_ipfs_offline`, `status_ipfs_failed`,
`label_ipfs_mode`, `label_ipfs_external_host`, `label_ipfs_external_port`,
`label_ipfs_storage_max`, `message_ipfs_unsupported_abi`.

---

## 10. Permisos y manifest

Modo embebido: **ningún permiso nuevo**. `INTERNET`, `FOREGROUND_SERVICE` y
`FOREGROUND_SERVICE_DATA_SYNC` ya están, y el daemon vive dentro de
`ProxySupervisorService`, que ya es un foreground service `dataSync`.

Modo externo: tampoco.

Solo una futura automatización de Termux exigiría
`com.termux.permission.RUN_COMMAND`, `REQUEST_INSTALL_PACKAGES` y un bloque
`<queries>` para `com.termux`. **Fuera de alcance de este plan.**

Se mantiene la regla de AGENTS.md: nada de nuevos intent-filters de launcher.

---

## 11. Fases

### Fase 1 — Runtime (sin UI)
- `tools/fetch-kubo.sh`, `.gitignore`, paso de CI.
- `IpfsSettings`, `IpfsRuntime`, `IpfsHealthClient`.
- Enganche en `ProxySupervisorService` con hilo propio; campos en `ServiceState`.
- Verificación: activar por preferencia a mano, ver `ipfs:` en el log y
  `curl http://127.0.0.1:8080/ipfs/<CID>` desde adb devolviendo bytes.

### Fase 2 — UI
- `buildIpfsPanel()`, strings ES/EN, estado en `HapStatus` y `refreshRuntimeState()`.
- Verificación: el switch enciende y apaga en frío y en caliente; sobrevive a
  girar pantalla y a cerrar/abrir la actividad; en TV se navega con D-pad.

### Fase 3 — Reproducción
- `resolveIpfsUrl` + `MSG_PREPARE_PLAYBACK` + `MSG_REWRITE_CAST_URL`.
- Verificación: reproducir un CID de vídeo conocido desde StreamVault, y el
  mismo por Chromecast con servidor LAN activo.

### Fase 4 — Listas y modo externo (según DECISIÓN 1 y 3)
- Modo nodo externo en la UI.
- Validación M3U con targets IPFS, si se aprueba.

### Fase 5 — Cierre
- `README.md`, `docs/Changelog.md`, subida de `versionCode`/`versionName` en
  `app/build.gradle.kts` **y** en los dos sitios del `AndroidManifest.xml`
  (metadata del plugin y JSON del manifest).
- `./gradlew :app:assembleDebug` y `:app:lintDebug` limpios.
- Prueba en dispositivo real: móvil arm64 y una Android TV.

---

## 12. Riesgos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| **Tamaño del APK** | Splits por ABI, `-s -w`, posible arm64-only (§8) |
| **Batería y datos móviles** | Perfil `lowpower`, `ConnMgr` limitado, sin relay. Opción "solo Wi-Fi" que para el daemon al perder Wi-Fi (fase 2 o 3) |
| **Android mata el proceso hijo** | Ya vivimos dentro de un foreground service; el supervisor debe reintentar el arranque como hace con AceServe |
| **Choque de puertos** | Gateway configurable; comprobar puerto libre antes de arrancar y avisar en la UI |
| **Consumo de disco** | `Datastore.StorageMax` por defecto 2 GB + `--enable-gc`; botón "vaciar caché IPFS" (borra `blocks/`) como mejora |
| **Migración de repo entre versiones de kubo** | `daemon --migrate=true`; subir el sufijo del marcador `.prepared-ipfs-vN` cuando haya cambio incompatible |
| **`repo.lock` huérfano tras un kill** | Detectar y limpiar antes de arrancar, con traza en el log |
| **Contenido IPFS lento o inexistente** | El gateway se queda colgado; poner timeout y mensaje claro en lugar de un reproductor congelado |
| **Dispositivo sin ABI soportada** | Panel IPFS deshabilitado con `message_ipfs_unsupported_abi`; el resto de HaP intacto |

---

## 13. Decisiones pendientes (resumen)

1. **Modo "nodo externo"**: ¿fase 1 o posterior? → recomendación: fase 1.
2. **Gateway LAN**: ¿reutiliza el switch "servidor LAN" existente? → recomendación: sí.
3. **Validación M3U con targets IPFS**: ¿se relaja `SourceValidator`? → recomendación: sí, pero en fase 4 y con pruebas propias.
4. **ABIs de kubo**: ¿solo `arm64-v8a`? → recomendación: sí de entrada.
5. **Versión de kubo a fijar**: propuesta `v0.43.0`. Verificar la estable del momento antes de empezar.

---

## 14. Notas para el agente que retome esto

- Lee primero `AGENTS.md`. Sus reglas mandan sobre este documento en caso de
  conflicto, en especial: strings EN/ES sincronizados, versión sincronizada en
  los cuatro sitios, no tocar los zips de ABI ni `libacepython.so`, y no
  anunciar `configuration.schema`.
- El modelo a copiar es `AceServeRuntime` + `ProxyExposure` + el patrón de panel
  de `HapConfigActivity`. Si algo en `IpfsRuntime` no se parece a
  `AceServeRuntime`, probablemente esté mal.
- No hay tests en el repo (`app/src/test` y `app/src/androidTest` no existen).
  La verificación es `:app:assembleDebug`, `:app:lintDebug` y prueba en
  dispositivo del flujo tocado.
- No empieces por la UI. Sin `IpfsRuntime` funcionando por adb, el panel es un
  switch que no hace nada.
