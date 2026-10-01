# Seguimiento de ríos (java-tron b33eed8)

Cada fila es una raíz de diseño. Se mide (puntos de contacto), se formula **una hipótesis que pueda
refutarse** y se anota qué prueba la cerraría. **No contiene ningún hallazgo.** Las cifras salen de
`grep` y de scripts de expresiones regulares: son orientativas, no un análisis semántico.

Fecha de las mediciones: 2026-10-01. Entorno: Java 8 (OpenJDK 1.8.0_504), Gradle 7.6.4, Semgrep 1.178.0.

## Verificación hecha en esta sesión

- Las **48 pruebas** `*RevertTest` (Stake 2.0) se ejecutaron en este entorno: **48 pasan, 0 fallan**
  (`org.tron.common.runtime.vm.*RevertTest`, módulo `framework`, commit `3399552`).
  Desglose: 12 + 10 + 6 + 6 + 6 + 4 + 2 + 2.

## Medidas de superficie (código principal, sin pruebas)

| Medida | Valor |
|---|---|
| Servlets HTTP | 133 |
| Métodos gRPC (`api.proto`, todos los servicios; techo, puede haber repetidos) | 203 |
| Métodos JSON-RPC | 52 |
| Manejadores de mensajes P2P | 9 |
| Archivos de actuadores nativos | 39 (36 con `execute` analizable) |
| Precompiladas de la TVM | 32 |

## Ríos

| Río | Contactos | Hipótesis (refutable) | Prueba que la cierra | Estado |
|---|---|---|---|---|
| R1 caché `isVerified` | 12 (7 `TransactionCapsule`, 5 `Manager`) | La verificación cacheada se reutiliza tras cambiar el estado | Revisar los puntos que la escriben o borran | **Cerrado**: `switchFork` la reinicia antes de reaplicar |
| R3 banderas de gobernanza | 443 consultas `allow*` / `VMConfig` | Alguna rama se comporta distinto con la bandera puesta y quitada | Pruebas con bandera activada y desactivada | Abierto, sin revisar en profundidad |
| R5 concurrencia | 273 (`ConcurrentHashMap`, ejecutores, hilos) + 101 `synchronized` | Estado compartido sin protección en la tubería de bloques | — | Abierto |
| R7 reversión | 60 | Revertir no descarta algún cambio | 48 pruebas | **Cerrado** |
| R-tiempo (CPU) | 27 | El resultado depende de la velocidad del nodo | — | Diseño conocido (ratio de tiempo); su comprobación exige carga, fuera de alcance |
| R8 configuración local → validez | 7 mandos locales en rutas de validación | Un tercero sin privilegios puede cambiar la validez con una opción local | Listar mandos y quién los controla | **Cerrado** (ver abajo) |

### R8: configuración local que afecta a la validez

Mandos locales leídos en rutas de validación de `actuator`, `chainbase` y `consensus`:
`isECKeyCryptoEngine` (24 lecturas, algoritmo de hash y firma de toda la red SM2),
`getZenTokenId`, `getMinTimeRatio` y `getMaxTimeRatio` (límite de CPU de la TVM), `isDebug` (quita el
límite de CPU en `Program`), `getCheckFrozenTime` y `getConstantCallTimeoutMs`.

- **Caso concreto:** `FreezeBalanceActuator.java:207` y `Program.java:1940` solo aplican el rango de
  duración de la congelación si `checkFrozenTime == 1`. El comentario del código dice "for test"; el
  valor por defecto es 1 (`reference.conf:770`).
- **Conclusión:** son decisiones del operador del nodo, no de un tercero. Exigen acceso a la
  configuración, así que no son alcanzables sin privilegios. Queda como observación de diseño: reglas
  de consenso que dependen de un mando "solo para pruebas".
- 51 de las lecturas de `CommonParameter` están en `DynamicPropertiesStore` y son valores iniciales.

## Matriz de actuadores (qué valida cada uno)

Medida con un script sobre `validate()`/`execute()`. Heurística: cuenta patrones, no entiende la lógica.

- **Matemática segura:** `Exchange*`, `ParticipateAssetIssue` y `Transfer` usan `addExact`/`subtractExact`.
  La familia congelar, descongelar, retirar y cancelar suma con `+` sobre `long` en `execute`
  (por ejemplo `UnfreezeBalanceActuator`, `WithdrawBalanceActuator`, `CancelAllUnfreezeV2Actuator`).
- **Pregunta:** ¿puede desbordarse alguna de esas sumas o multiplicaciones con entradas válidas?
- **Lo comprobado hasta ahora:** `getFrozenDays()` (AssetIssue, líneas 263-264) y `getFrozenDuration()`
  (FreezeBalance, 209-210) están acotados por mínimo y máximo antes de multiplicar por
  `FROZEN_PERIOD = 86_400_000`. La segunda cota depende del mando `checkFrozenTime` (ver R8). Los saldos
  están acotados por el suministro total (orden de 10^17 sun, lejos de 9,2·10^18). No se encontró un
  caso de desbordamiento con los valores por defecto.
- **Pendiente:** confirmar el argumento del suministro con cada suma y mirar los 4 actuadores `Exchange*`
  y `ShieldedTransfer`, cuya estructura de `validate()` es distinta y el script no la analiza bien.

## Límites

Lectura y medición, no auditoría. Que no se haya encontrado nada no prueba que no haya nada. Los
mantenedores siguen publicando arreglos en estas áreas. Todo el trabajo fue sobre código público y en
local; no se tocó ninguna red.
