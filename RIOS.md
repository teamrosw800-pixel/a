# Seguimiento de ríos (java-tron b33eed8)

Cada fila es una raíz de diseño. Se mide (puntos de contacto), se formula **una hipótesis que pueda
refutarse** y se anota qué prueba la cerraría. **No contiene ningún hallazgo.** Las cifras salen de
`grep` y de scripts de expresiones regulares: son orientativas, no un análisis semántico.

Fecha de las mediciones: 2026-10-01. Entorno: Java 8 (OpenJDK 1.8.0_504), Gradle 7.6.4, Semgrep 1.178.0.

## Verificación hecha en esta sesión

- Las **48 pruebas** `*RevertTest` (Stake 2.0) se ejecutaron en este entorno: **48 pasan, 0 fallan**
  (`org.tron.common.runtime.vm.*RevertTest`, módulo `framework`).
  **Sobre los dos commits:** `b33eed8` es el commit de java-tron (upstream) que se analiza. `3399552` es el
  commit de *este* repositorio, que contiene esa copia de java-tron más las pruebas y documentos. No son
  versiones distintas del código analizado.
  Desglose: 12 + 10 + 6 + 6 + 6 + 4 + 2 + 2.

## Medidas de superficie (código principal, sin pruebas)

| Medida | Valor |
|---|---|
| Servlets HTTP | 131 en `services/http/` + 2 en `services/http/solidity/` = **133**. La sección R4 analiza los 131 del directorio principal. Contando los espejos de PBFT y solidity, el árbol `services/` tiene 232 archivos `*Servlet.java` |
| Métodos gRPC (`api.proto`, todos los servicios; techo, puede haber repetidos) | 203 |
| Métodos JSON-RPC | 52 |
| Manejadores de mensajes P2P | 9 |
| Archivos de actuadores nativos | 39 (36 con `execute` analizable) |
| Precompiladas de la TVM | 32 |

## Ríos

| Río | Contactos | Hipótesis (refutable) | Prueba que la cierra | Estado |
|---|---|---|---|---|
| R1 caché `isVerified` | 12 (7 `TransactionCapsule`, 5 `Manager`) | La verificación cacheada se reutiliza tras cambiar el estado | Revisar los puntos que la escriben o borran | **Cerrado**: `switchFork` la reinicia antes de reaplicar |
| R3 banderas de gobernanza | 443 consultas `allow*` / `VMConfig`; 77 tipos de propuesta | Alguna rama se comporta distinto con la bandera puesta y quitada | Pruebas con bandera activada y desactivada | Parcial: medido; subcaso `HARDEN_RESOURCE` cerrado (ver abajo); el resto sin revisar |
| R9 fórmula duplicada, dos fuentes de bandera | 2 copias de las fórmulas de recursos | Las dos copias dejan de coincidir en el momento de activar la propuesta | Leer cuándo se refresca cada fuente y quién llama a cada copia | **Cerrado** para consenso (ver abajo) |
| R7 reversión | 60 | Revertir no descarta algún cambio | 48 pruebas | **Cerrado** |
| R-tiempo (CPU) | 27 | El resultado depende de la velocidad del nodo | — | Diseño conocido (ratio de tiempo); su comprobación exige carga, fuera de alcance |
| R11 precompiladas: coste real frente a energía cobrada | 34 precompiladas direccionables; `checkCPUTimeLimit` aparece 6 veces en el árbol | Alguna precompilada consume más CPU de la que cobra | Medir tiempo por unidad de energía en cada una, en local | **Medido**: desproporción real en Blake2F, encuadre DoS, no reportable (ver abajo) |
| R10 coma flotante en consenso | 56 apariciones de `double` en 22 archivos; 5 usos de `pow` | El consenso depende de la precisión de `double` en algún cálculo que mueva valor | Propiedades (salida acotada, monótona, ida y vuelta) con oráculo y medición de error | **Medido** (ver Intercambio): error acotado, ≈2e-12 de la reserva por ida y vuelta |
| R4 APIs HTTP (131 servlets) | 131 servlets, 203 métodos gRPC, 52 JSON-RPC | Alguna puerta HTTP no limita el tamaño del cuerpo | Comparar las guardas de los 131 servlets y el límite del servidor | **Cerrado**: límite global (ver abajo) |
| R5 concurrencia (estado compartido mutable) | 98 campos de clase con colección no concurrente; 4 compartidos y mutables en ejecución | Un campo mutable lo tocan hilos distintos sin cerrojo común, con efecto sobre el estado de cadena | Clasificar los 98 y mirar quién escribe y quién lee | **Medido**: 4 candidatos, sin efecto sobre consenso (ver abajo) |
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

## R3: banderas de gobernanza (medición)

Se cruzaron los 77 tipos de `ProposalUtil.ProposalType` con sus consultas en el código principal
(emparejando nombres sin distinguir mayúsculas ni guiones bajos).

- **11** propuestas tienen 2 líneas consumidoras o menos; **9** no aparecen en ninguna prueba.
- **Corrección propia:** una primera pasada dio varios "sin consumidor" que eran un fallo de
  emparejamiento. `TOTAL_CURRENT_ENERGY_LIMIT` se lee como `getTotalEnergyCurrentLimit` (palabras en
  otro orden, 10 usos). Cualquier cifra de esta sección es orientativa.
- Las propuestas más recientes (códigos 94 a 98) tienen entre 2 y 15 líneas consumidoras y entre 3 y 9
  archivos de prueba cada una.

### Subcaso `ALLOW_HARDEN_RESOURCE_CALCULATION` (97) y R9

La propuesta sustituye cuatro fórmulas de recursos de `RepositoryImpl` (lado TVM: `usageToBalance`,
`increase`, `getUsage`, `calculateGlobalEnergyLimit`) y las equivalentes de `ResourceProcessor`
(lado procesadores). La versión antigua usa `double` o multiplica `long` sin comprobar desbordamiento; la
nueva usa `BigInteger` con `longValueExact`. Es un indicio de qué cálculos consideraron mejorables los
mantenedores.

- **Dos fuentes de la bandera:** el lado TVM la lee de `VMConfig` (una instantánea que
  `ConfigLoader.load` rellena desde el almacén); el lado procesadores la lee en vivo de
  `DynamicPropertiesStore`.
- **Hipótesis:** una ruta de consenso usa la fórmula del lado TVM antes de que se cargue la instantánea,
  y por tanto con un valor antiguo.
- **Resultado: refutada.** `ConfigLoader.load` solo se llama desde `VMActuator.validate()` (línea 125),
  el primer paso de toda transacción de contrato, antes de cualquier uso. Las funciones del lado TVM
  solo las llaman la TVM (`ContractState`, `FreezeV2Util`) y `VMActuator`. La llamada de `Wallet.java`
  que parecía usar la fórmula del lado TVM usa `EnergyProcessor`, que lee el almacén en vivo.
- **Observación de diseño:** `VMConfig.globalSnapshot` nace con todo a `false`. Cualquier llamador futuro
  fuera de una transacción de contrato leería valores por defecto, no los de la cadena. Hoy no existe.
- **Prueba hecha:** la prueba diferencial descrita en la sección siguiente.

### Prueba diferencial de las dos copias (hecha)

Archivo: `tests/ResourceFormulaDifferentialTest.java` (va en `framework/src/test/java/org/tron/core/db/`
de java-tron; se ejecuta con Java 8 y
`./gradlew :framework:test --tests org.tron.core.db.ResourceFormulaDifferentialTest -i`).
Entradas aleatorias con semilla fija. Los `assert now > lastTime` del código solo actúan en pruebas, por
lo que se generan solo entradas con `now >= lastTime`.

| Comparación | Casos | Discrepancias |
|---|---|---|
| `increase`, bandera apagada (`RepositoryImpl` frente a `EnergyProcessor`) | 20.000 | 0 |
| `increase`, bandera encendida (11.853 con excepción en ambas) | 20.000 | 0 |
| `getUsage`, bandera apagada y encendida | 40.000 | 0 |

**Las dos copias coinciden.** Con esto la hipótesis de R9 queda cerrada también por prueba, no solo por lectura.

**Alcance real de la prueba (corregido el 2026-10-01).** La propuesta 97 cambia **cuatro** fórmulas, y esta
prueba compara solo dos (`increase` y `getUsage`). Las otras dos (`usageToBalance` y
`calculateGlobalEnergyLimit`) **ya están cubiertas por las pruebas que trae el propio PR upstream**
(`RepositoryImplHardenTest`, `ResourceProcessorHardenTest` y `CalculateGlobalLimitHardenTest`), con
oráculos en `BigInteger`. Ampliar la prueba diferencial a esas dos habría duplicado cobertura existente.
También se verificó que la asimetría V1/V2 entre el lado TVM y chainbase está contemplada como esperada en
esas pruebas.

### Límite de la prueba diferencial y oráculo independiente

Las dos copias son idénticas, así que una prueba que las compara no detecta un defecto que ambas
comparten. Por eso se añadió un oráculo en `BigInteger` (caso `lastTime == now`, sin decaimiento).

- **Observación:** en las dos copias, incluso con el endurecimiento activo, la línea
  `averageLastUsage += averageUsage;` es una suma de `long` sin comprobar. Cada término se comprueba por
  separado (`longValueExact`), pero **su suma no**. Si cada uno cabe en 64 bits y la suma no, el
  resultado se enrolla a negativo sin excepción.
- **Resultado del oráculo:** 5.026 casos con uso hasta 1e12: **0 violaciones**.
  **Corrección del número usado como cota (2026-10-01):** aquí se dijo "unas 10 veces el límite total de
  energía de la red" sin anotar el valor. El valor real de mainnet es
  `TotalEnergyCurrentLimit = 1,8e11`, así que 1e12 son **5,6 veces** ese límite, no 10. La conclusión no
  cambia (0 violaciones por debajo de esa cota), pero el factor citado era incorrecto. Fuera de
  esa cota, 14 violaciones sobre 34.974 casos, todas con el patrón anterior. Ejemplo:
  `increase(7524290872464, 8206654683304, 5, 5, 1)` devuelve `-2715798517941` en lugar de lanzar excepción.
- **Alcance:** exige un uso de recursos de entre 1e12 y 1e18 y, en algunos casos, ventanas de 1 slot. El uso
  real lo acotan los límites totales de energía y ancho de banda (orden de 1e10 a 1e11) y el
  gasto máximo por transacción. **No hay una vía realista que lo alcance.**
- **Conclusión:** endurecimiento incompleto en una zona inalcanzable, no una vulnerabilidad. No se
  considera apto para reportar: no hay impacto demostrable con valores posibles. Queda anotado como
  pregunta de diseño para los mantenedores.

## Dónde se cruzan los ríos (medido)

Archivos de código principal que coinciden con el patrón de cada río; la intersección dice dónde dos
ríos comparten código. Los patrones son expresiones regulares: cifras orientativas (por ejemplo `R1`
sale con 1 archivo aquí y con 2 en el recuento anterior, por la forma del patrón).

| Archivos | Intersección |
|---|---|
| 33 | banderas ∩ aritmética |
| 26 | banderas ∩ configuración local |
| 18 | concurrencia ∩ configuración local |
| 16 | concurrencia ∩ P2P |
| 15 | configuración local ∩ aritmética |
| 12 | configuración local ∩ APIs |

**Archivos que tocan 5 ríos a la vez:** `core/db/Manager.java` (R1, R3, R5, R7, R8),
`core/vm/program/Program.java`, `core/actuator/VMActuator.java` y `core/vm/PrecompiledContracts.java`
(R3, CPU, R8, aritmética y reversión o concurrencia). Con `RepositoryImpl.java`, `Args.java` y
`RelayService.java` (4 ríos) son los puntos donde un cambio en un río puede afectar a otro.

Un fallo que encadene ríos tendría que pasar por alguno de estos archivos. Que dos ríos compartan un
archivo no implica que exista una cadena explotable: solo marca dónde mirar.

## Intercambio (R10): propiedades de `ExchangeProcessor` y `SafeExchangeProcessor`

Prueba: `tests/ExchangeInvariantTest.java` (va en `framework/src/test/java/org/tron/core/capsule/`).
200.000 casos por implementación, semilla fija, reservas de 1e3 a 1e15.

| Propiedad | Antigua (`ExchangeProcessor`) | Segura (`SafeExchangeProcessor`) |
|---|---|---|
| I1 salida entre 0 y la reserva | 0 violaciones | 0 violaciones |
| I3 monótona (más entrada, no menos salida) | 0 violaciones | 0 violaciones |
| I2 ida y vuelta no devuelve más de lo enviado | 5.907 casos (≈3 %) | 5.794 casos (≈3 %) |
| Ganancia máxima de una ida y vuelta / reserva | 1,98e-12 | 1,92e-12 |

- **Lectura:** la versión "segura" sigue usando `double` para `pow(base, 0.0005)` y `pow(base, 2000)`
  (`SafeExchangeProcessor`). Su objetivo es el determinismo entre nodos y evitar desbordamiento, no la
  exactitud: el error relativo de `double` (≈1e-16) se amplifica por 2000. Resultado: una ida y vuelta puede
  devolver hasta ≈2e-12 de la reserva más de lo enviado. Para cantidades muy pequeñas respecto a la
  reserva el error relativo sobre la propia cantidad es grande.
- **Valoración:** es un error de redondeo ("polvo"): acotado, del mismo orden en las dos versiones, y
  cada intento exige una transacción con su coste. No se considera apto para reportar y no se
  documentan entradas concretas. Queda como observación: `I2` solo se puede garantizar hasta esa cota.
- La prueba afirma I1, I3 y que la ganancia de ida y vuelta no supere 1e-11 de la reserva (hoy 2e-12).

## APIs HTTP (R4): consistencia de guardas entre los 131 servlets

Medido con un script sobre `framework/.../services/http/*Servlet.java`.

| Medida | Valor |
|---|---|
| Servlets con `doPost` | 129 |
| Usan `PostParams.getPostParams` (que ya llama a `checkBodySize`) | 87 |
| Llaman a `checkBodySize` directamente | 23 |
| Leen el cuerpo a mano (`getReader`/`getInputStream`) | 25 |
| Con `doPost` que no leen cuerpo (consultas sin parámetros) | 18 |
| Servlets que manejan el error por su cuenta en lugar de `Util.processError` | 9 |

**Las categorías se solapan:** 87 + 23 + 25 + 18 = 153 > 129, porque una misma clase puede caer en varias
(por ejemplo, un servlet que usa `PostParams` en `doPost` y además lee el cuerpo a mano en otro método).
No son conjuntos disjuntos.

- **Valor atípico:** `BroadcastHexServlet` lee el cuerpo a mano y no llama a `checkBodySize` ni usa
  `PostParams`. **Hipótesis:** esa puerta no tiene límite de tamaño. **Refutada:** cada servicio HTTP
  (`HttpService.java:91`) envuelve todo en un `SizeLimitHandler(maxRequestSize, -1)` de Jetty, con el valor de
  `httpMaxMessageSize` (4 MB por defecto), y lo usan los cuatro servicios (completo, solidity, PBFT y sobre
  solidity). `checkBodySize` queda como segunda guarda, redundante con la global.
- **Manejo de errores:** los 9 servlets que no usan `processError` (por ejemplo `TriggerSmartContractServlet`,
  `EstimateEnergyServlet`) devuelven igualmente la clase de la excepción y su mensaje, en otro formato.
  Es un diseño uniforme: divulga el nombre de la clase de excepción. Impacto bajo; no se considera reportable.
- **No revisado:** límites de frecuencia en JSON-RPC (desactivado por defecto) y en gRPC, ni la
  validación de parámetros dentro de cada servlet.

### R3: propuestas sin ninguna prueba que las mencione

Son 9 de 77. Las 8 reales son **parámetros de valor** de la época de lanzamiento (comisiones y pagos,
códigos entre 5 y 47), no interruptores de comportamiento: `CREATE_NEW_ACCOUNT_FEE_IN_SYSTEM_CONTRACT`,
`MULTI_SIGN_FEE`, `MAX_FEE_LIMIT`, `WITNESS_STANDBY_ALLOWANCE`, `WITNESS_PAY_PER_BLOCK`,
`UPDATE_ACCOUNT_PERMISSION_FEE`, `WITNESS_127_PAY_PER_BLOCK`, `ADAPTIVE_RESOURCE_LIMIT_TARGET_RATIO`.
Sin rama "activada/desactivada" el riesgo de comportamiento distinto es bajo. La novena,
`TOTAL_CURRENT_ENERGY_LIMIT`, sale vacía por el fallo de emparejamiento ya descrito. La falta de mención
en pruebas no prueba que no estén cubiertas de forma indirecta.

## Concurrencia (R5): estado compartido mutable

Se listaron los campos de clase (no variables locales) con `HashMap`, `ArrayList`, `HashSet`,
`LinkedList`, `LRUMap`... y se descartó lo que lleva `Concurrent*`, `synchronized` o `CopyOnWrite`.
Son **98 campos** en el código principal, más 1 estático con `LRUMap` (`Program.programPrecompileLRUMap`,
ya analizado en el mapa: no lo usan las llamadas constantes y la clave incluye el hash del código).

Clasificación a mano de los 98:
- **La gran mayoría no se comparte entre hilos:** DTO y configuración, constructores de transacciones
  protegidas, cachés por instancia de `RepositoryImpl` (una instancia por ejecución), pilas y trazas de la
  TVM por programa, tablas estáticas que se llenan al cargar la clase.
- **Solo se escribe al arrancar:** `DposService.miners` (se llena en `start`).
- **Compartidos y mutables en ejecución (4):**

| Campo | Quién escribe | Quién lee | Valoración |
|---|---|---|---|
| `Manager.ownerAddressSet` (`HashSet`) | `pushBlock` y `pushTransaction`, bajo `synchronized(this)` | también `generateBlock` (hilo de producción, solo SR), donde no se vio un cerrojo | Solo nodos productores (acceso privilegiado). Una lectura inconsistente solo afectaría al bloque que ese SR produce. No se confirmó el cerrojo. |
| `WitnessProductBlockService.cheatWitnessInfoMap` (`HashMap`) | hilo que procesa bloques | hilo de la API de información del nodo, que recorre el mapa | Posible `ConcurrentModificationException` en una consulta informativa. Sin efecto sobre cadena. |
| `PeerConnection.syncBlockInProcess` (`HashSet`) | hilo de mensajes P2P (`add`) | hilo de sincronización (`remove`, `contains`), desconexión (`clear`) y métricas (`size`) | Carrera de datos sobre un conjunto simple. El efecto queda en el estado de sincronización **de esa conexión** y se limpia al desconectar. La misma familia que el issue #6969 y su PR #6983, abiertos. |
| `SnapshotManager.dbs` y `flushServices` | arranque | varios | Se llenan al inicio; no se revisó el acceso posterior. |

**Conclusión:** de 98 campos, 4 son candidatos reales y ninguno afecta al consenso de forma alcanzable sin
privilegios. Lo más cercano (`syncBlockInProcess`) está en una familia que los mantenedores ya tienen
abierta. Queda sin revisar la corrección de los cerrojos de `Manager` (la combinación `synchronized(this)`,
`transactionLock`, `forkLock` y los bloqueos de `pushBlock`), que es donde más ayuda el razonamiento que el
recuento.

## R11: precompiladas, coste real frente a energía cobrada

Medición hecha en una sesión paralela sobre `develop 4a21592f95`, con los cuatro archivos implicados
verificados como idénticos a `b33eed8`. **Los datos estructurales de abajo los volví a comprobar yo contra
el código; los tiempos medidos no los he reproducido en este entorno.**

### Censo: son 34, no 32 ni 35 (verificado aquí)

Tres recuentos independientes coinciden en **34**: campos `private static final DataWord ...Addr` = 34,
clases con `getEnergyForData(byte[])` = 34, e instancias devueltas por `getContractForAddress` = 34.
- El 32 del mapa venía de un `grep` por `extends PrecompiledContract`, que pierde las tres que heredan de
  la clase intermedia `VerifyProof`.
- Un recuento de 35 incluiría `VerifyProof`, que es `public abstract static class` (línea 1248) y no tiene
  dirección: no es invocable.

### Dónde se comprueba el tiempo de CPU (verificado aquí)

`checkCPUTimeLimit` / `getCPUTimeLeftInNanoSecond` aparecen **6 veces** en todo el código principal:
`Program.java:1252` (el cuerpo), `VM.java:87`, `VMActuator.java:202`,
`PrecompiledContracts.java:481` (el ayudante), `:1202` y `:1589`.

En el bucle de opcodes la comprobación va **antes** de ejecutar (`VM.java:87` comprueba, `:90` ejecuta).
Por tanto el tope de 80 ms (`getMaxCpuTimeOfOneTx`) se comprueba **entre** opcodes: una precompilada corre
entera y el exceso se detecta después, cuando el tiempo ya se gastó. Solo **2 de las 34** miran el reloj
por dentro:
- `BatchValidateSign` (`:1202`) lo lee y lanza `notEnoughTime` si se agota. Guarda viva.
- `VerifyTransferProof` (`:1589`) asigna `boolean withNoTimeout = countDownLatch.await(...)` y **nunca lo
  lee**. Guarda muerta. Verificado leyendo las líneas 1585-1600.

### El caso con más desproporción: Blake2F

`getEnergyForData` devuelve `rounds.longValue()`, donde `rounds` sale de los **4 primeros bytes de la
entrada**, que elige quien llama: **1 energía por ronda** (verificado aquí).
Las mediciones de la sesión paralela dan un coste marginal de ~21 ns por ronda, es decir unos **41 ns por
unidad de energía**, frente a ~0,02 ns/energía de `Identity` con entrada grande. Una llamada de 1e7 rondas
tardó ~411 ms gastando el tope de energía de una transacción.

**Tope de energía por transacción (verificado aquí):** `maxFeeLimit` por defecto = `1_000_000_000` sun y
`DEFAULT_ENERGY_FEE` = 100 sun/energía ⇒ **1e7 de energía por transacción**. (Una cifra de 15.000 TRX que
se manejó en la sesión paralela era inventada y quedó corregida.)

**Valoración:** la desproporción es real y medible, pero el efecto es consumo de CPU por encima de lo
cobrado, es decir **denegación de servicio**. El atacante paga su propia energía en cada intento y el tope
por transacción acota cada llamada. Encaja con el issue que los propios mantenedores ya tienen abierto
sobre comprobaciones de tiempo de grano fino en precompiladas, que nombra `BatchValidateSign` y
`VerifyTransferProof`. **No se considera reportable** y no se desarrolla más aquí.

### Rama de decaimiento de `increase` (completa el hueco de R10)

El oráculo de la sección anterior solo cubría `lastTime == now`. La sesión paralela midió la otra rama, la
que aplica `round(averageLastUsage * decay)` en `double`:

- `Math.round` **satura** a `Long.MAX_VALUE` en vez de desbordar, y la suma posterior sigue sin comprobarse,
  así que puede salir un valor negativo sin excepción. `getUsage(-1000000, 28800) = -28800` cabe en `long`,
  de modo que `longValueExact` tampoco lo detecta.
- En 4.648 casos del barrido: **TVM y chainbase divergen 0 veces** (no hay divergencia de consenso) y ambas
  divergen del oráculo exacto en 176 casos.
- **Cota de alcanzabilidad:** con la bandera en el estado real de mainnet (la propuesta 97 **no está
  activada**), el valor negativo exige `usage > 9,22e12`, que es **922.337 veces** la energía máxima de una
  transacción (1e7) y unas **51 veces** el `TotalEnergyCurrentLimit` de 1,8e11.
- **Conclusión:** misma familia que lo ya anotado. Defecto de endurecimiento incompleto, inalcanzable por
  varios órdenes de magnitud. No reportable.

### Dato que corrige una suposición anterior

La medición inicial de la sesión paralela se hizo con `allowHardenResourceCalculation = 1`, pero en mainnet
la propuesta 97 **está a 0**: lo que corre hoy es la rama *no* endurecida. El ejemplo
`increase(7524290872464, 8206654683304, 5, 5, 1) = -2715798517941` documentado más arriba se reprodujo
contra el código real en esa rama viva, con el mismo valor.

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
