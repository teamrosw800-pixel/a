# Stake 2.0 y reversión: `UNFREEZEBALANCEV2`

**Propiedad comprobada (solo esta):** los cambios de un `UNFREEZEBALANCEV2` válido de Stake 2.0 que
tiene éxito se descartan cuando la ejecución que lo contiene revierte después (mismo marco de
ejecución, `REVERT` explícito).

**Resultado:** `UnfreezeV2RevertTest` **pasa** (6 de 6, 0 fallos). En este escenario el comportamiento
observado es el correcto. No se ha encontrado ninguna discrepancia y no se afirma ninguna
vulnerabilidad ni gravedad.

Este informe no modifica los anteriores (`STAKE_V2_REVERT_REPORT.md`,
`STAKE_V2_DELEGATE_REVERT_REPORT.md`, `STAKE_V2_UNDELEGATE_REVERT_REPORT.md`,
`STAKE_V2_NESTED_REVERT_REPORT.md`), cuyas conclusiones siguen limitadas a sus operaciones.
**El caso anidado (operación ejecutada por un contrato llamado) no se ha probado todavía para
`UNFREEZEBALANCEV2`.**

## 1. Por qué esta operación necesita más escenarios

`UnfreezeBalanceV2Processor.execute` hace bastante más que bajar el saldo congelado, y cada parte es
un sitio donde una reversión podría dejar restos:

- **Cobra los desbloqueos ya vencidos:** los paga al saldo disponible y los quita de la lista. Si la
  reversión no lo deshiciera, se perderían o duplicarían fondos.
- **Añade una entrada pendiente** con su caducidad y baja el peso global del recurso.
- **Liquida la recompensa de votación:** escribe ciclo de inicio, ciclo de fin y una instantánea de
  votos por ciclo en `DelegationStore`, y sube la `allowance` de la cuenta.
- **Con poder de TRON, recalcula o borra los votos** y reescribe el `VotesStore`; en cuentas
  anteriores al nuevo modelo de recursos (poder antiguo positivo) borra todos los votos de golpe e
  invalida el poder antiguo.

## 2. Versión y configuración

| Dato | Valor |
|---|---|
| Origen | https://github.com/tronprotocol/java-tron (instantánea sin historial) |
| Commit upstream | `b33eed89a6a424c498d4fb1b03ca2c86eddf4840` (2026-09-11), `BLOCK_VERSION` 37 |
| Rama / commit de la prueba | `claude/pensive-mayer-ni6me2`, prueba en `45cd944` (sin cambios posteriores) |
| Plataforma | Linux x86_64, OpenJDK 1.8.0_504 |
| Build | `./gradlew` (Gradle 7.6.4 fijado por el proyecto), `--no-daemon --max-workers=1` |
| Configuración de red de la prueba | `config-test.conf` + `--debug` (desactiva el límite de CPU de la VM, como `FreezeV2Test`) |

Configuración explícita de la prueba: `allowTvmFreeze=1`, `allowNewResourceModel=1`,
`allowDelegateResource=1`, `unfreezeDelayDays=30`, `currentCycleNumber=5`, las banderas TVM de
`FreezeV2Test` con `AllowTvmFreezeV2=1` y `AllowTvmVote=1`, y `latestBlockHeaderTimestamp` fijado
explícitamente (T0 = 1 700 000 000 000 ms; los escenarios con historial lo avanzan).

## 3. Cobertura existente revisada

`FreezeV2Test` prueba los fallos de validación de deshacer el congelado y los caminos con éxito con
votos (`testUnfreezeVotes`, `testUnfreezeWithOldTronPower`, `testUnfreezeWithoutOldTronPower`,
`testUnfreezeTronPowerWithOldTronPower`), pero ninguna comprueba «la operación tiene éxito y después
la ejecución revierte». `UnfreezeBalanceV2ActuatorTest` cubre la ruta nativa sin VM. No había una
prueba equivalente que reutilizar.

## 4. Entorno

Sin errores de entorno: 0 respuestas 429 y compilación correcta en las tres ejecuciones. Los fallos
de entorno de la primera comprobación están en `STAKE_V2_REVERT_REPORT.md`.

## 5. Pruebas ejecutadas

| Ejecución | Contenido | Resultado |
|---|---|---|
| 1 | `UnfreezeV2RevertTest` (5 escenarios) + `FreezeV2Test` + `UnfreezeBalanceV2ActuatorTest` | Línea base: `FreezeV2Test` 8/8 y `UnfreezeBalanceV2ActuatorTest` 19/19 pasan. La clase nueva: **fallan los 5**, por un error de la prueba (ver sección 8). |
| 2 | `UnfreezeV2RevertTest` con las expectativas corregidas | 5 de 5 pasan |
| 3 (final) | `UnfreezeV2RevertTest` (6 escenarios) + las cuatro clases de reversión anteriores | 6/6, y `FreezeV2RevertTest` 2/2, `DelegateResourceRevertTest` 2/2, `UnDelegateResourceRevertTest` 4/4, `NestedStakeV2RevertTest` 12/12 |

Un solo intento por invocación y sin reintentos de pruebas en las ejecuciones 2 y 3. El escenario
sexto se añadió después de leer los resultados de la ejecución 2 (sección 7) y solo se ha ejecutado
en la 3.

## 6. La prueba

`framework/src/test/java/org/tron/common/runtime/vm/UnfreezeV2RevertTest.java`.

Un contrato mínimo en bytecode TVM, con un ensamblador con etiquetas dentro de la prueba, controlado
por calldata (`op 0` congela, `op 3` desbloquea). En `op 3`, **dentro de la misma ejecución y antes de
terminar**, el contrato lee seis valores del estado en curso con precompilados de consulta y
`BALANCE`, y los devuelve con `RETURN` o con `REVERT` según un parámetro:

| Palabra | Contenido |
|---|---|
| 0 | resultado de `UNFREEZEBALANCEV2` |
| 1 | saldo congelado V2 propio del recurso (`0x0100000d`) |
| 2 | huecos libres para desbloqueos pendientes (`0x0100000c`) |
| 3 | pendientes ya vencidos en el instante dado (`0x0100000e`) |
| 4 | saldo disponible (`BALANCE`) |
| 5 | votos en uso (`0x01000008`) |

### Escenarios

1. **Ancho de banda** y 2. **energía**, con historial construido por la propia VM avanzando la hora de
   cabecera: congelar; desbloquear A (3 TRX) en T0 (caduca a los 30 días); desbloquear B (2 TRX) en
   T0+20 días; y ejecutar la prueba en T0+35 días, cuando A ya venció y B no. La operación probada
   desbloquea 1 TRX y por tanto **cobra A**.
3. **Poder de TRON con votos y recompensa, desbloqueo de la mitad** y 4. **desbloqueo total**: dos
   votos de 500, ciclos de la cuenta 2..3, ciclo actual 5 y índices de recompensa de los dos testigos
   sembrados, de modo que se liquida una recompensa de 1 500 000 SUN. En la mitad los votos se
   recalculan a 250; en el total se borran.
5. **Energía en una cuenta con poder de TRON antiguo positivo y un voto** (ruta de migración): se
   borran todos los votos de golpe, se crea el registro del `VotesStore` y se invalida el poder antiguo.
6. **Primer desbloqueo de una cuenta sin votos ni historial.**

En los escenarios 3, 4 y 5 los votos y los ciclos se siembran escribiendo directamente en los
almacenes, como hace `FreezeV2Test`; no se producen con `VOTEWITNESS` dentro de la VM.

### Cómo se demuestra que la operación ocurrió antes de la reversión

En la ejecución revertida el dato del `REVERT` contiene las seis palabras y la prueba exige que
coincidan con lo esperado. Valores observados:

| Escenario | Palabras 0–5 en la ejecución revertida |
|---|---|
| ancho de banda / energía (con vencido) | 1, 4 000 000, 30, 0, saldo + 3 000 000, 0 |
| poder de TRON, mitad | 1, 500 000 000, 31, 0, saldo, 500 |
| poder de TRON, total | 1, 0, 31, 0, saldo, 0 |
| energía, poder antiguo | 1, 600 000 000, 31, 0, saldo, 0 |
| primer desbloqueo | 1, 9 000 000, 31, 0, saldo, 0 |

En los dos primeros, la palabra 4 demuestra que el vencido ya se había cobrado dentro de la ejecución
(el saldo sube 3 TRX) y la palabra 3 que ya no quedan vencidos; antes de la operación el estado
tenía A vencido, comprobado en la preparación. Además el recibo es `REVERT` y el tiempo de ejecución
está marcado como revertido.

**La liquidación de recompensas no tiene testigo en la ejecución.** La consulta de recompensa devuelve
pendiente más acumulada, así que no cambia cuando la recompensa se cobra. Queda evidenciada por el
control (la `allowance` sube exactamente la recompensa calculada, 1 500 000) y porque la operación
devolvió 1, ya que la liquidación se ejecuta sin salida temprana al principio del mismo `execute`.

### Qué se compara

Antes de la ejecución revertida, después de ella y después de un control:

- **La cuenta:** saldo disponible, `allowance`, poder de TRON antiguo, saldos congelados V2
  (ancho de banda, energía, poder de TRON), la lista de desbloqueos pendientes completa
  (tipo, importe y caducidad), la lista de votos y la cuenta serializada completa.
- **`VotesStore`:** el registro de votos de la cuenta.
- **`DelegationStore`:** ciclo de inicio, ciclo de fin e instantánea de votos del ciclo actual.
- **Pesos globales** (ancho de banda, energía, poder de TRON).

**Tras la reversión todo es idéntico al estado previo, en los seis escenarios.**

### Control (misma llamada, sin reversión, ejecutada después)

Valores observados (impresos por la prueba; las expectativas correspondientes sí son aserciones):

| Escenario | Saldo disponible | `allowance` | Pendientes | Votos | Ciclos inicio/fin | Poder antiguo |
|---|---|---|---|---|---|---|
| ancho de banda / energía | +3 000 000 (cobra A) | 0 → 0 | 2 → 2 (A fuera, nueva dentro) | — | 6/−1 → 6/−1 | −1 → −1 |
| poder de TRON, mitad | igual | 0 → 1 500 000 | 0 → 1 | 2 → 2 (250 cada uno) | 2/3 → 5/6 | −1 → −1 |
| poder de TRON, total | igual | 0 → 1 500 000 | 0 → 1 | 2 → 0 | 2/3 → 5/6 | −1 → −1 |
| energía, poder antiguo | igual | 0 → 0 | 0 → 1 | 1 → 0 | 0/−1 → 5/6 | 1 000 000 000 → −1 |
| primer desbloqueo | igual | 0 → 0 | 0 → 1 | — | 0/−1 → 6/−1 | −1 → −1 |

Además se comprueba en cada control el saldo congelado, el contenido exacto de la lista de pendientes
(incluida la caducidad `ahora + 30 días`), el peso global (baja lo desbloqueado en TRX), el registro
del `VotesStore` (votos nuevos recalculados o borrados, votos viejos intactos) y la instantánea de
votos del ciclo (creada donde hay votos, ausente donde no).

### Costes que el protocolo permite conservar

Se conserva la energía consumida: el saldo del remitente baja **exactamente** `receipt.getEnergyFee()`
y `getEnergyUsageTotal() > 0`, en la ejecución revertida y en el control. Es la única diferencia de
saldos permitida. El cobro de un desbloqueo vencido **no** es un coste conservable: es cambio de
estado y desaparece con la reversión, y así ocurre (el saldo vuelve al valor previo). El ancho de
banda de la transacción lo cobra el procesador de bloques, que este banco de pruebas no ejecuta.

## 7. Un hueco de cobertura encontrado y cerrado

Al leer los resultados de la ejecución 2 se vio que en los escenarios de ancho de banda y energía
los ciclos valían `6/−1` antes **y** después de la operación: la liquidación de recompensas no
escribía nada en `DelegationStore` durante la operación probada, porque el primer desbloqueo de la
preparación ya había adelantado el ciclo de inicio. Pero el **primer desbloqueo de una cuenta sin
votos** sí escribe en `DelegationStore`, y ese almacén lo toca todo desbloqueo. Se añadió el sexto
escenario, cuyo control muestra el cambio `0/−1 → 6/−1`.

## 8. Errores de la prueba durante el desarrollo (no eran del código)

- **Ejecución 1: fallaron los 5 escenarios por una expectativa errónea mía**, antes de cualquier
  aserción sobre la reversión. Olvidé que congelar **descuenta** el TRX del saldo disponible: 20 TRX en
  los escenarios de ancho de banda y energía y 1000 TRX en los de votos. Los valores reales
  (1e17 − 2·10⁷ y 1e17 − 10⁹) coincidían exactamente con eso, y la prueba existente ya asumía ese
  descuento. En los escenarios de votos las palabras 0–3 de las lecturas en curso ya coincidían antes
  de fallar la palabra 4. Se corrigió derivando las expectativas del estado inicial capturado y
  comprobando explícitamente ese saldo inicial.
- **Un supuesto mío falso descubierto al leer el código, corregido antes de ejecutar:** creí que el
  poder de TRON antiguo valdría 0 antes del desbloqueo, pero el propio congelado
  (`FreezeBalanceV2Processor`) ya lo inicializa como inválido (−1). Por eso los escenarios de
  poder de TRON no ejercitan esa inicialización, y se añadió el escenario de migración para cubrirla.

## 9. Qué demuestra el resultado y qué no

- **La operación se ejecutó de verdad antes de revertir.** Las lecturas en curso coinciden con lo
  esperado en los seis escenarios (salvo la recompensa, ver arriba).
- **Cada comparador puede detectar una fuga, al menos en un escenario.** Saldo disponible (cobro del
  vencido), `allowance` (recompensa), poder antiguo (migración), saldos congelados, lista de
  pendientes, votos de la cuenta, registro del `VotesStore` (recalculado, borrado y creado), ciclos de
  inicio y fin, instantánea de votos y pesos globales cambian en el control de algún escenario, así
  que la comparación tras la reversión habría fallado si algo se hubiera filtrado.
- **Limitación de esa sensibilidad:** es «en algún escenario», no en cada uno; por ejemplo, en los
  escenarios de ancho de banda y energía las comparaciones de votos, `VotesStore` y `allowance` no se
  ven cambiar.
- **Sin prueba de mutación** sobre el código de la VM ni del procesador.

## 10. Conclusión limitada a este escenario

En el commit `b33eed8`, con esta configuración de pruebas unitarias y en una plataforma (Linux
x86_64, Java 8), un `UNFREEZEBALANCEV2` válido de ancho de banda, energía o poder de TRON, con un
desbloqueo vencido que se cobra, con votos que se recalculan o se borran (incluida la ruta de
migración), con una recompensa que se liquida o con el primer desbloqueo de una cuenta, seguido por
un `REVERT` explícito del mismo marco de ejecución, **no deja cambios** en la cuenta (saldo,
`allowance`, congelados, pendientes, votos, poder antiguo), en el `VotesStore`, en `DelegationStore`
ni en los pesos globales. Solo se conserva la comisión de energía del llamador.

Clasificación: **comportamiento correcto**. No hay discrepancia del código ni problema de la prueba
que se conozca (ver las limitaciones de la sección 9).

## 11. Lo que esta prueba NO cubre

- **El caso anidado:** `UNFREEZEBALANCEV2` ejecutado por un contrato llamado, con reversión del
  externo o del interno. Es la laguna más importante que queda para esta operación.
- Otras operaciones de Stake 2.0: `WITHDRAWEXPIREUNFREEZE`, `CANCELALLUNFREEZEV2`.
- Desbloquear con recursos delegados presentes (el peso global usa congelado más delegado), el límite
  de 32 desbloqueos pendientes y la ruta de delegaciones inválidas (`hasInvalidDelegatedV2`).
- Poder de TRON con historial de desbloqueos vencidos y pendientes (el historial solo se probó con
  ancho de banda y energía).
- Recompensas con más de un tramo de ciclos, con instantánea previa de votos (la rama que la usa) o con
  comisión del testigo; la recompensa probada sale de la rama sin instantánea previa.
- Votos producidos con `VOTEWITNESS` dentro de la VM: se sembraron en los almacenes.
- Otras causas de fallo distintas de `REVERT` (`OUT_OF_ENERGY`, `OUT_OF_TIME`, excepciones de VM),
  varias operaciones en la misma transacción, y `DELEGATECALL`/`CALLCODE`/`STATICCALL`.
- El registro de transacciones internas y de eventos de la ejecución revertida (no es estado
  persistido), el procesamiento real de bloques, el cobro de ancho de banda y la red.
- Un solo commit y una sola plataforma.

## 12. Cómo reproducirlo

```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 PATH=$JAVA_HOME/bin:$PATH
cd java-tron
./gradlew --no-daemon --max-workers=1 :framework:test \
  --tests "org.tron.common.runtime.vm.UnfreezeV2RevertTest"
```
