# Mapa de java-tron: cómo funciona y qué cambian los mantenedores

**Esto es un mapa, no un informe de seguridad.** Se construyó leyendo el código público y el historial
público de git de la rama `develop` en el commit `b33eed8` (2026-09-11; comprobado el 2026-09-30 que es
la última). No contiene ningún hallazgo. Sirve para decidir por dónde empezar la siguiente
comprobación.

**Cómo leerlo.** Cada afirmación está marcada:
- **[código]**: la leí en el código de ese commit.
- **[historial]**: sale del historial de git (asuntos de commits; solo abrí los cuerpos que se indican).
- **[juicio]**: es mi interpretación y puede estar equivocada.

## 0. Límites de este mapa

- El clon del historial es parcial: **214 commits desde 2025-01-01**. Lo anterior no se ve.
- Cuento y clasifico por el **asunto** del commit. Solo leí el cuerpo de los que se citan.
- Los arreglos que se hayan entregado en privado o sin la palabra "security" no aparecen.
- No leí todo el código (unas 134.000 líneas Java en `src/main`), solo las rutas citadas.
- La ficha del programa (HackerOne `tron_dao`) no se puede abrir desde este entorno; usé el texto
  que pegaste. Su lista de "fuera de alcance" es genérica y orientada a web [texto pegado].

## 1. Cómo funciona

### 1.1 Módulos [código]

| Módulo | Archivos / líneas Java | Qué hace |
|---|---|---|
| `framework` | 478 / 53.678 | Nodo completo: red P2P, APIs (HTTP, gRPC, JSON-RPC), `Manager` (tuberías de bloques y transacciones), eventos, métricas |
| `actuator` | 108 / 25.811 | Ejecuta transacciones: 42 actuadores nativos + la máquina virtual (TVM: `vm/`, 62 archivos) |
| `chainbase` | 157 / 24.884 | Estado: almacenes (`store/`), cápsulas (`capsule/`), capas de instantáneas (`db2/`), `KhaosDatabase` (ramas) |
| `common` | 155 / 14.939 | Parámetros, configuración, excepciones, utilidades, `VMConfig` |
| `crypto` | 36 / 7.509 | Firmas (ECKey, SM2), keystore, zk |
| `plugins` | 31 / 4.583 | Herramientas de base de datos (lite, mover, etc.) |
| `consensus` | 19 / 1.861 | DPoS y PBFT |
| `protocol` | solo `.proto` | Definiciones de mensajes y contratos |

### 1.2 Vida de una transacción [código]

```
API HTTP/gRPC/JSON-RPC ─┐
                        ├─> Wallet.broadcastTransaction ─> TronNetDelegate.pushTransaction
P2P (TransactionsMsgHandler, colas + pool de hilos) ─────┘        │
                                                                   v
                                           Manager.pushTransaction  (sincronizado)
                                             └ processTransaction:
                                               validateTapos, validateCommon, validateDup,
                                               validateSignature  (usa el atajo isVerified),
                                               TransactionTrace ─> Runtime
                                                  ├ actuador nativo (transfer, freeze, voto, ...)
                                                  └ VMActuator ─> Program (TVM)
                                             └ pendingTransactions / rePushTransactions
```

### 1.3 Vida de un bloque [código]

- **Recibido:** `BlockMsgHandler` → `TronNetDelegate.processBlock` → `Manager.pushBlock` (bajo
  `synchronized(this)`): `getVerifyTxs` decide qué firmas hay que volver a verificar,
  `khaosDb.push` guarda el bloque en la ficha de ramas, si cambia la rama principal `switchFork`, y
  `applyBlock`/`processBlock` ejecuta: `preValidateTransactionSign` (firmas en paralelo contra el estado
  de inicio del bloque) y después cada transacción con `processTransaction`.
- **Producido (solo un SR en su turno):** `DposTask` → `Manager.generateBlock`, que saca
  transacciones de la lista pendiente, salta las de un dueño que ya tiene un cambio de permisos en ese
  bloque y vuelve a verificar la firma si el dueño está en `ownerAddressSet`.
- **Consenso:** 27 SR activos (`MAX_ACTIVE_WITNESS_NUM = 27`), mantenimiento periódico
  (`MaintenanceManager`), solidificación con umbral del 70 % y capa PBFT.

### 1.4 Estado y reversión [código]

- `chainbase/db2` (`SnapshotManager`, `Chainbase`) apila sesiones sobre RocksDB/LevelDB. Cada bloque y
  cada transacción usan una sesión que se confirma o se descarta.
- La TVM usa otra capa: `RepositoryImpl` (1.264 líneas) con cachés propias e hijos que se confirman con
  `commit()`. La serie de 48 pruebas de esta sesión comprobó que revertir descarta los cambios de las 6
  operaciones Stake 2.0 (validación, no hallazgo).

### 1.5 Cómo se activan las funciones nuevas [código]

Toda función nueva va tras una **propuesta de gobernanza**: `ProposalService` tiene 80 casos (por ejemplo
`ALLOW_TVM_OSAKA`, `ALLOW_TVM_PRAGUE`, `ALLOW_HARDEN_RESOURCE_CALCULATION`); un `ProposalController`
la procesa cuando `hasMostApprovals` sobre los SR activos; el valor pasa a `DynamicPropertiesStore` y
`ConfigLoader` lo copia a `VMConfig`, que consultan `OperationRegistry`, `PrecompiledContracts` y
`Program`. Consecuencia [juicio]: cada TIP nuevo añade código y ramas condicionadas por bandera, y el
nodo tiene que comportarse igual con la bandera puesta y quitada.

## 2. Por dónde entra la gente

| Entrada | Quién puede usarla | Por defecto [config.conf] | Código |
|---|---|---|---|
| P2P TCP 18888 | Cualquier par | activo | `core/net/` |
| gRPC 50051 (solidity 50061) | Quien llegue al puerto | `rpc.enable = true` | `RpcApiService.java` |
| HTTP 8090 (solidity 8091) | Quien llegue al puerto | `fullNodeEnable = true` | `core/services/http/` |
| JSON-RPC 8545 | Quien llegue al puerto | **`httpFullNodeEnable = false`** | `core/services/jsonrpc/` |
| Transacción firmada | Cualquier cuenta con saldo o recursos | — | `Manager.pushTransaction` |
| Contrato desplegado | Cualquier cuenta | — | `VMActuator`, `Program` |
| Producir bloques | Solo los 27 SR | — | `DposTask`, `generateBlock` |
| Propuestas | Solo los SR | — | `ProposalService` |
| `SolidityNode` | Se fía de su full node configurado a propósito | `trustNode = 127.0.0.1:50051` | `pushVerifiedBlock` |

### Lo que dice el programa (texto pegado, última actualización de la ficha: 15-jun-2025)

- **Dentro de alcance:** la integridad del protocolo, con nombre y apellido "mecanismo de consenso,
  modelo de recursos, TVM, APIs, implementación del protocolo", la seguridad clásica del cliente, las
  primitivas criptográficas y "la mayoría de métodos HTTPS, gRPC y JSON-RPC". Código de todas las ramas.
- **Prohibido:** denegación de servicio y explotación activa contra las redes TRON; ingeniería social.
- **Encadenar está permitido:** "un fallo por informe, salvo que necesites encadenar para dar impacto".
- **Una raíz, una recompensa:** "varias vulnerabilidades causadas por un mismo problema de fondo se
  premian con una sola". Encontrar muchas instancias del mismo río no multiplica el pago.
- **Duplicados:** solo se premia el primer informe, si se reproduce por completo. Piden pasos
  reproducibles.
- **Fuera de alcance:** lista genérica de web (clickjacking, CSRF sin acción sensible, MITM, banners de
  versión, etc.) y "ataques que requieren acceso a direcciones privilegiadas (gobernanza...)". No dice
  nada expreso de "por diseño".
- **Plazos:** primera respuesta 5 días laborables, triaje 10, recompensa 14.
- **Divulgación:** no discutir el contenido de los informes fuera del programa, ni siquiera los que no
  eran vulnerabilidades. Para detalles de alcance escriben a bounty@tron.network.
- **Mi restricción de trabajo:** solo local y en pruebas; nada contra la red real.

## 3. Qué cambian los mantenedores [historial]

- **214 commits desde 2025-01-01**: 55 `fix`, 52 `feat`, 18 `refactor`, 14 `test`, 22 sin prefijo
  (versiones, plantillas), el resto CI/docs.
- **Ritmo**: 2026-02: 11, 03: 18, 04: 21, **05: 74**, 06: 29, 07: 9, 08: 3, 09: 6. Mayo de 2026 concentra
  un tercio del historial, con muchos commits de endurecimiento [juicio: parece una campaña; el
  historial no dice por qué].
- **Por ámbito**: `vm` 32, `config` 14, `jsonrpc` 12, `net` 10, `api` 7, `db` 4, `security` 3, `crypto` 3.
- **Archivos con más arreglos** (commits `fix` que tocan código principal, 48 en total; "fix" no
  significa seguridad): `Manager.java` 11, `TronJsonRpcImpl.java` 7, `Wallet.java` 6,
  `PrecompiledContracts.java` 4, `TronNetDelegate.java` 4, `SolidityNode.java` 4.

### Familias de cambio

| # | Familia | Commits (ejemplos) |
|---|---|---|
| F1 | **Caché de validez de firma (`isVerified`)** | 4f41f26 (#6716), 78bc75d (#6777), 2c50400 (#6796), 7ab8945 (#6864) |
| F2 | **Funciones nuevas de la TVM tras propuesta** (TIP-2935, TIP-7883 ModExp, TIP-854 calldata, TIP-871, TIP-7951 P256, TIP-7939 CLZ, EIP-7823, CREATE2 profundidad, TIP-833 recursos) | 32 commits `vm`; arreglos tras aterrizar: b5b8ee5 y 156af72 (TIP-2935, dos veces), af88269, 0f9fc76, a8fa3d4 |
| F3 | **Determinismo entre nodos** | f92a6a5 (`Locale.ROOT`: en sistemas turcos/azeríes `toLowerCase()` cambia `I`; incluye una migración de claves), 54342dc ("quitar la lista blanca de actuadores para evitar bifurcación"; cuerpo vacío, solo el asunto), cf144f3 (`StrictMathWrapper` en la aritmética de TIP-7883; por asunto, no leí el cuerpo) |
| F4 | **Estado que se corrompe en casos límite** | ba2b77f (`TrieImpl.insert()` con clave duplicada corrompía el hash raíz; cuerpo leído), 59b1339 (tres actuadores desempaquetaban el tipo de contrato equivocado en `getOwnerAddress()`; cuerpo leído) |
| F5 | **Concurrencia y ciclo de vida** | ba5b012 (`MerkleTree` singleton compartido → carrera; cuerpo leído), 107490c, a41321c, 980c707, 6f63a8c |
| F6 | **Límites y endurecimiento de bordes** (API, JSON-RPC, gRPC, P2P) | unos 20 `fix(api/jsonrpc/net)`: 2fd5455, 95d84d3, ea3ffb4, 1691fdd, 3a9ccfe, 01441dc, bc6b26f... |
| F7 | **Cripto de transacciones protegidas** | 97bffb3 (nonce ligado al nullifier), 5ef7de6, b38c35c |
| F8 | Configuración, CI, documentación | (sin interés para este análisis) |

## 4. Ríos: raíz que se queda, síntomas que se parchean

Un "río" es una decisión de diseño que el proyecto no va a quitar; los arreglos van a las instancias.
Criterios para decidir si vale la pena: (1) ¿quién llega, alguien sin privilegios?; (2) ¿qué impacto
tendría, división de la cadena o fondos frente a denegación de servicio o filtración?; (3) ¿la raíz sigue
ahí o los arreglos ya la quitaron?; (4) ¿se puede comprobar en local sin red real ni DoS?; (5) ¿está
concurrido, se está arreglando ahora mismo?; (6) ¿el programa lo admite?

| Río | Raíz (decisión de diseño) | Evidencia | Quién llega | Impacto típico | Estado | Juicio inicial [juicio] |
|---|---|---|---|---|---|---|
| **R1 Atajo de firma** | Ir rápido: guardar el resultado de verificar | F1 (4 arreglos) | Productor de bloques (SR) para los caminos que quedaron | Aceptar tx con firma inválida | **Leído en esta sesión: 4 escenarios cubiertos o solo alcanzables por un SR** | Aparcado; se reabre si aparece un camino nuevo |
| **R2 Determinismo entre nodos** | Todos los nodos deben calcular exactamente lo mismo | F3, F4, F5 | Cualquiera que envíe transacciones o despliegue contratos | División de la cadena | Sin revisar | **Sí, prioridad alta**: es la clase clásica de blockchain, con alcance sin privilegios y prueba en local |
| **R3 Código nuevo tras propuesta** | Cada TIP añade código y ramas por bandera | F2 (32 commits, 5 arreglos posteriores) | Cualquier contrato | Energía, estado, consenso | Sin revisar | **Sí**: código nuevo, menos revisado |
| **R4 Bordes expuestos** | Servicios que parsean datos ajenos | F6 (~20) | Cualquiera que llegue al puerto | Sobre todo DoS y filtración | Sin revisar | **No por ahora**: el programa prohíbe DoS, el impacto suele ser bajo y una raíz paga una sola vez |
| **R5 Tubería multihilo** | `Manager` con 42 construcciones de concurrencia | F5 | Cualquiera (por P2P) | Carreras, estado inconsistente | Sin revisar | Quizá, después de R2 |
| **R6 Cripto protegida** | Cripto compleja y a medida | F7 | Cualquiera con transacciones protegidas | Fondos | Sin revisar | Quizá; requiere especialización |
| **R7 Capas de reversión** | Repositorios anidados con caché | Serie de 48 pruebas | Cualquier contrato | Estado tras revertir | **Comprobado: sin discrepancia** | Cerrado |

## 5. Siguiente paso propuesto (a decidir con el usuario) — sustituido por §6.3

(Texto original conservado. Tras el barrido de §6 la prioridad cambió.)

Empezar por **R2 + R3 juntos**: la pregunta común es *"¿hay algún cálculo o rama que dependa de algo que
no es igual en todos los nodos, o que se comporta distinto con la bandera puesta y quitada?"*. Método:
lectura dirigida de lo que tocan F3, F4 y F2, pruebas locales con la bandera activada y desactivada, y
regla de parada de 3 escenarios sin discrepancia por sub-área. Cualquier candidato real se mantiene
privado, como exige la política de divulgación del programa.

## 6. Revisión 1 (2026-09-30): barrido de determinismo y calibración externa

Solo lectura del código de `b33eed8` y de fuentes públicas. **No hay ningún hallazgo.** Cada punto está
marcado [código], [razonamiento] o [fuente].

### 6.1 Barrido de "resultados que dependen de la máquina": qué se descartó y por qué

| Hipótesis | Qué comprobé | Resultado |
|---|---|---|
| Funciones trascendentes de `Math` (pow, exp, log...) dan distinto en x86 y ARM | `grep` en `actuator`, `chainbase`, `consensus`, `crypto`, `common` | **Ninguna** fuera de `StrictMath` [código] |
| El límite de tiempo de CPU de la TVM hace que el resultado dependa de la velocidad del nodo | `VMActuator.getCpuLimitInUsRatio`, `Program.checkCPUTimeLimit`, `TransactionTrace.checkNeedRetry/check` | Es **por diseño**: el productor usa ratio 1,0; el validador usa `maxTimeRatio`, o `minTimeRatio` si el bloque registró `OUT_OF_TIME`; si no coincide hay reintento y luego `ReceiptCheckErrException`. Es la raíz "por ser rápido" y no se puede demostrar sin DoS [código] |
| Trabajo de CPU que la energía no cubre | `MUtil` tiene 7 comprobaciones de tiempo sueltas (hash de campos, 0x0a, create2, modExp, FreezeBalanceV2 tras SELFDESTRUCT, delegado V2 inválido...) | Familia que se parchea **caso a caso**; sigue siendo un río, pero su impacto típico es denegación de servicio [código] |
| Decodificar bytes con el juego de caracteres por defecto (`new String(bytes)`) | ids de token en `RepositoryImpl` y `Program` | No alcanzable: solo importa para juegos de caracteres compatibles con ASCII y un id de token válido es solo dígitos [razonamiento] |
| Caché estática no sincronizada `programPrecompileLRUMap` (`LRUMap` no es segura entre hilos) | `Program.getProgramPrecompile` | Las llamadas constantes (las de la API) **no la usan** (compilan en local) y la clave es dirección + hash del código, así que no queda obsoleta [código] |
| Asimetría productor/validador como la de TIP-2935 (b5b8ee5 admite que existió) | Comparé lo que hacen `generateBlock` y `processBlock` antes del bucle de transacciones | Solo `saveBlockEnergyUsage(0)` difiere; la simulación se descarta y ese contador solo alimenta el cálculo adaptativo posterior [código] |
| Configuración local que cambia resultados de consenso | Lecturas de `CommonParameter` en actuator, chainbase y consensus | Casi todo son constantes de red; `isECKeyCryptoEngine` cambia el hash del precompilado SHA-256 en redes SM2 (toda la red, no lo explota un atacante) [código] |

### 6.2 Calibración externa [fuente]

- **Auditoría de ChainSecurity (agosto 2024)**, según los resúmenes que pude abrir (el informe original está
  bloqueado desde aquí): los tres hallazgos más significativos fueron *PBFT Messages Create State
  Expansion*, *Unpermissioned Censoring of Fork Blocks* y *Resource Consumption by Blocks Not Signed by
  Witnesses*, todos corregidos. Son de la **capa P2P y de consenso, alcanzables por cualquier par**.
- **Issue público #6354** (curva inválida en `ECKey.decompressKey()`, propuesto como reclamación de
  recompensa): aparece cerrado y en lo que pude leer no hay respuesta documentada de los mantenedores.
  Un argumento criptográfico verosímil no se premia por sí solo.
- **Issue #6994** (23-sep-2026): los mantenedores proponen endurecer `ECKey` (validación de claves, reglas
  de validez de puntos). No cita ningún informe concreto.
- **GitHub Security Advisories**: ninguno publicado para java-tron (comprobado antes en esta sesión).

### 6.3 Nuevo ranking (sustituye a §5)

| Prioridad | Río | Por qué |
|---|---|---|
| **Alta** | **R4' P2P y consenso: "aceptar antes de validar"** | Es donde los auditores encontraron lo más significativo; lo alcanza cualquier par sin privilegios; los mantenedores siguen parcheando ahí (F6: `net` 10 commits, `security` 3); se puede probar en local con un nodo y mensajes fabricados, sin red real |
| Media | R5 tubería multihilo | Se cruza con R4' (mismos hilos y colas) |
| Media-baja | R3 código nuevo tras propuesta | Barrido de determinismo sin candidatos; sigue siendo código reciente |
| Baja | R2 determinismo entre nodos | Barrido §6.1 sin candidatos |
| Aparcados | R1, R7 | Ya revisados |

Raíz de R4' [juicio]: el nodo trabaja sobre datos de un par no autenticado antes de haber demostrado que el
atacante pagó un coste; cada arreglo cierra un mensaje o un límite, la raíz sigue.

### 6.4 Severidad (cálculo CVSS 3.1 propio, orientativo; el programa decide con su criterio)

- DoS remoto sin autenticación (`AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H`): **7,5, alto**.
- Aceptar un bloque o transacción inválidos (`.../C:N/I:H/A:H`): **9,1, crítico**.
- Ambos exigirían un candidato real reproducido en local.

### 6.5 Pregunta de alcance abierta

La ficha enlaza "Core Ineligible Findings" (la lista estándar de HackerOne) y desde aquí no se puede abrir.
Antes de invertir en R4' hay que confirmar si la **denegación de servicio a nivel de aplicación** (un solo
mensaje que agota memoria o bloquea el nodo) es elegible. La ficha dice que se puede escribir a
bounty@tron.network para detalles de alcance. Las pruebas serían solo locales (un nodo propio), nunca contra
la red real.
