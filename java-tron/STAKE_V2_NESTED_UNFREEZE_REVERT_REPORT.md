# Stake 2.0 y reversión en marcos anidados: `UNFREEZEBALANCEV2`

**Propiedad comprobada (solo esta):** los cambios de un `UNFREEZEBALANCEV2` válido de Stake 2.0,
ejecutado por un contrato **llamado**, se descartan cuando revierte el marco de ejecución que
contiene la operación, en dos formas:

- **Revierte el externo:** el llamado ejecuta la operación y termina bien (su marco se fusiona con el
  del llamador) y después revierte el llamador. Revierte toda la transacción.
- **Revierte el interno:** el llamado ejecuta la operación y después revierte; el llamador ve que la
  llamada falla, continúa y termina bien. La transacción **tiene éxito y se confirma**, así que
  cualquier fuga desde el marco interno quedaría persistida.

**Resultado:** `NestedUnfreezeV2RevertTest` **pasa** (10 de 10, 0 fallos). En este escenario el
comportamiento observado es el correcto. No se ha encontrado ninguna discrepancia y no se afirma
ninguna vulnerabilidad ni gravedad.

Este informe no modifica los anteriores. Cierra la laguna que `STAKE_V2_UNFREEZE_REVERT_REPORT.md`
listaba como «no cubierto» para esta operación. Los motivos de cada escenario están en ese informe.

## 1. Versión y configuración

| Dato | Valor |
|---|---|
| Origen | https://github.com/tronprotocol/java-tron (instantánea sin historial) |
| Commit upstream | `b33eed89a6a424c498d4fb1b03ca2c86eddf4840` (2026-09-11), `BLOCK_VERSION` 37 |
| Rama / commit de la prueba | `claude/pensive-mayer-ni6me2`, prueba en `35650c0` (sin cambios posteriores) |
| Plataforma | Linux x86_64, OpenJDK 1.8.0_504 |
| Build | `./gradlew` (Gradle 7.6.4 fijado por el proyecto), `--no-daemon --max-workers=1` |
| Configuración de red de la prueba | `config-test.conf` + `--debug` (desactiva el límite de CPU de la VM, como `FreezeV2Test`) |

Configuración explícita: la misma que en `UnfreezeV2RevertTest` (`allowTvmFreeze=1`,
`allowNewResourceModel=1`, `allowDelegateResource=1`, `unfreezeDelayDays=30`, `currentCycleNumber=5`,
banderas TVM de `FreezeV2Test` con `AllowTvmFreezeV2=1` y `AllowTvmVote=1`, y
`latestBlockHeaderTimestamp` fijado en T0 = 1 700 000 000 000 ms, avanzado en el escenario con
historial). El estado de partida se construye igual que en las pruebas del mismo marco, con llamadas
directas al contrato llamado; los votos, los ciclos y los índices de recompensa se siembran
escribiendo en los almacenes, como hace `FreezeV2Test`.

## 2. Cobertura existente revisada

Entre las pruebas que ejercitan `UNFREEZEBALANCEV2` a nivel de contrato (`FreezeV2Test`, `FreezeTest`),
ninguna usa una llamada anidada (búsqueda heurística por nombre, no exhaustiva). Las pruebas del
mismo marco de esta serie no se han modificado.

## 3. Entorno

Sin errores de entorno: 0 respuestas 429, compilación correcta, `BUILD SUCCESSFUL`. Los fallos de
entorno de la primera comprobación están en `STAKE_V2_REVERT_REPORT.md`.

## 4. Pruebas ejecutadas

Una sola invocación de Gradle, un intento, sin reintentos de pruebas, sobre el mismo commit:

| Clase | Pruebas | Resultado |
|---|---|---|
| `NestedUnfreezeV2RevertTest` (nueva) | 10 | 10 pasan |
| `UnfreezeV2RevertTest` (repetida) | 6 | 6 pasan |
| `NestedStakeV2RevertTest` (repetida) | 12 | 12 pasan |
| `FreezeV2RevertTest` (repetida) | 2 | 2 pasan |
| `DelegateResourceRevertTest` (repetida) | 2 | 2 pasan |
| `UnDelegateResourceRevertTest` (repetida) | 4 | 4 pasan |

Pasó a la primera. En esta ocasión no hubo ningún error de la prueba durante el desarrollo.

## 5. La prueba

`framework/src/test/java/org/tron/common/runtime/vm/NestedUnfreezeV2RevertTest.java`.

Dos contratos mínimos en bytecode TVM, con un ensamblador con etiquetas dentro de la prueba:

- **STAKE (el llamado, dueño del stake):** según calldata congela o desbloquea. Tras desbloquear, y
  dentro de la misma ejecución, lee seis valores del estado en curso (saldo congelado propio, huecos
  libres de pendientes, vencidos, `BALANCE`, votos en uso) y los devuelve con `RETURN` o `REVERT`.
- **CALLER:** llama a STAKE con esos datos, copia en su respuesta los datos que devolvió (la VM copia
  los datos de retorno también cuando el llamado revierte) más el indicador de éxito de la llamada, y
  termina con `RETURN` o `REVERT` según otro parámetro.

### Escenarios (cada uno, con «revierte el externo» y «revierte el interno»)

1. **Ancho de banda con historial:** un desbloqueo ya vencido (que la operación cobra al saldo) y
   uno pendiente, construidos con la propia VM avanzando la hora de cabecera.
2. **Poder de TRON con votos y recompensa, desbloqueo de la mitad** y 3. **total**: los votos se
   recalculan o se borran y se liquida una recompensa de 1 500 000 SUN.
4. **Energía en una cuenta con poder de TRON antiguo positivo y un voto** (migración): se borran los
   votos de golpe y la operación **crea** el registro del `VotesStore`.
5. **Primer desbloqueo de una cuenta** sin votos ni historial: escribe el ciclo de inicio en
   `DelegationStore`.

Se eligieron los escenarios donde un desbloqueo escribe en más almacenes, para que las capas de
repositorio anidadas tengan más que hacer mal. La energía con historial no se repite: es paralela al
ancho de banda.

### Cómo se demuestra que la operación ocurrió antes de la reversión

Los datos que llegan hasta la transacción de nivel superior contienen el resultado de la operación y
las cinco lecturas en curso; la prueba exige que coincidan con lo esperado en ambas formas. Valores
observados en la ejecución revertida (palabras 0–5) y el indicador de la llamada (palabra 6):

| Escenario | Palabras 0–5 | Indicador «revierte el externo» / «revierte el interno» |
|---|---|---|
| ancho de banda con vencido | 1, 4 000 000, 30, 0, saldo + 3 000 000, 0 | 1 / 0 |
| poder de TRON, mitad | 1, 500 000 000, 31, 0, saldo, 500 | 1 / 0 |
| poder de TRON, total | 1, 0, 31, 0, saldo, 0 | 1 / 0 |
| energía, poder antiguo | 1, 600 000 000, 31, 0, saldo, 0 | 1 / 0 |
| primer desbloqueo | 1, 9 000 000, 31, 0, saldo, 0 | 1 / 0 |

Las palabras coinciden con las del control anidado y con las del mismo marco. En «revierte el
interno» la transacción termina en `SUCCESS` y en «revierte el externo» en `REVERT`; ambas cosas se
comprueban. Como en el informe del mismo marco, **la liquidación de recompensas no tiene testigo en la
ejecución** (la consulta de recompensa devuelve pendiente más acumulada): queda evidenciada por el
control y por haber devuelto 1 la operación.

### Qué se compara

Antes de la ejecución revertida, después de ella y después de un control: **la cuenta del llamado**
(saldo disponible, `allowance`, poder antiguo, congelados V2, lista completa de pendientes, votos y
cuenta serializada completa), **la cuenta del contrato llamador** (saldo y serializada completa),
**el registro del `VotesStore`**, **`DelegationStore`** (ciclos de inicio y fin e instantánea de
votos) y **los pesos globales**.

**Tras la reversión todo es idéntico al estado previo, en las 10 pruebas.** En «revierte el interno»
esto tiene especial valor: la transacción se confirma y aun así nada del marco interno queda
persistido, incluido el registro del `VotesStore` que el marco interno habría creado.

### Control (llamada anidada idéntica, sin ninguna reversión)

La llamada anidada sí persiste el cambio. Valores observados:

| Escenario | Saldo disponible | `allowance` | Pendientes | Votos | Ciclos inicio/fin | `VotesStore` |
|---|---|---|---|---|---|---|
| ancho de banda con vencido | +3 000 000 | 0 → 0 | 2 → 2 | — | 6/−1 → 6/−1 | ausente |
| poder de TRON, mitad | igual | 0 → 1 500 000 | 0 → 1 | 2 → 2 | 2/3 → 5/6 | presente |
| poder de TRON, total | igual | 0 → 1 500 000 | 0 → 1 | 2 → 0 | 2/3 → 5/6 | presente |
| energía, poder antiguo | igual | 0 → 0 | 0 → 1 | 1 → 0 | 0/−1 → 5/6 | ausente → **creado** |
| primer desbloqueo | igual | 0 → 0 | 0 → 1 | — | 0/−1 → 6/−1 | ausente |

Además se comprueban el contenido exacto de la lista de pendientes, el peso global, los votos del
registro y la instantánea del ciclo, como en el informe del mismo marco. La cuenta del contrato
llamador no cambia en ningún brazo.

### Costes que el protocolo permite conservar

Se conserva la energía consumida por la transacción, se confirme esta o no: el saldo del remitente
baja **exactamente** `receipt.getEnergyFee()` y `getEnergyUsageTotal() > 0`, en la ejecución
revertida y en el control. Es la única diferencia de saldos permitida. El cobro del desbloqueo
vencido no es un coste conservable y desaparece con la reversión. El ancho de banda de la
transacción lo cobra el procesador de bloques, que este banco de pruebas no ejecuta.

## 6. Qué demuestra el resultado y qué no

- **La operación se ejecutó de verdad antes de revertir, en el marco interno.** Las lecturas en curso
  coinciden con lo esperado en las 10 pruebas y llegan al nivel superior por los datos de retorno.
- **Las dos formas de reversión se distinguen** por el indicador de la llamada y por el resultado de
  la transacción.
- **Cada comparador puede detectar una fuga, al menos en un escenario:** saldo disponible (cobro del
  vencido), `allowance` (recompensa), poder antiguo (migración), congelados, lista de pendientes, votos,
  registro del `VotesStore` (recalculado, borrado y creado), ciclos, instantánea y pesos globales
  cambian en el control de algún escenario.
- **Sensibilidad no demostrada:** es «en algún escenario», no en cada uno. Y la cuenta del contrato
  llamador nunca cambia en ningún brazo, así que su comparación no prueba que detecte una fuga.
- **Los controles anidados** comprueban los mismos campos principales que los del mismo marco.
- **Sin prueba de mutación** sobre el código de la VM ni del procesador.

## 7. Conclusión limitada a este escenario

En el commit `b33eed8`, con esta configuración de pruebas unitarias y en una plataforma (Linux
x86_64, Java 8), un `UNFREEZEBALANCEV2` válido ejecutado por un contrato llamado, en los cinco
escenarios descritos, **no deja cambios** en la cuenta del llamado, en la del llamador, en el
`VotesStore`, en `DelegationStore` ni en los pesos globales cuando revierte el marco que contiene la
operación, tanto si el que revierte es el llamador tras un llamado que terminó bien como si es el
propio llamado con el llamador continuando y confirmando la transacción. Solo se conserva la
comisión de energía.

Clasificación: **comportamiento correcto**. No hay discrepancia del código ni problema de la prueba
que se conozca (ver las limitaciones de la sección 6).

## 8. Lo que esta prueba NO cubre

- Otras operaciones de Stake 2.0: `WITHDRAWEXPIREUNFREEZE` y `CANCELALLUNFREEZEV2`, en cualquier marco.
- Más de dos niveles de anidamiento.
- Que un marco externo tenga sus **propios** cambios de Stake que deban conservarse mientras el
  interno se descarta (que no se descarte de más). Solo se comprueba que no se conserve de más.
- `DELEGATECALL` y `CALLCODE`, donde el contexto y el dueño del stake cambian, y `STATICCALL`.
- Energía con historial de pendientes, poder de TRON con historial, desbloquear con recursos
  delegados presentes, el límite de 32 pendientes y la ruta de delegaciones inválidas.
- Recompensas con más de un tramo de ciclos, con instantánea previa de votos o con comisión del
  testigo; votos producidos con `VOTEWITNESS` dentro de la VM (se sembraron en los almacenes).
- Otras causas de fallo distintas de `REVERT` (`OUT_OF_ENERGY`, `OUT_OF_TIME`, excepciones de VM).
- El registro de transacciones internas y de eventos, el procesamiento real de bloques, el cobro de
  ancho de banda y la red.
- Un solo commit y una sola plataforma.

## 9. Cómo reproducirlo

```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 PATH=$JAVA_HOME/bin:$PATH
cd java-tron
./gradlew --no-daemon --max-workers=1 :framework:test \
  --tests "org.tron.common.runtime.vm.NestedUnfreezeV2RevertTest"
```
