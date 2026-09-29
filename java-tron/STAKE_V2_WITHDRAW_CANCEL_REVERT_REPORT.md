# Stake 2.0 y reversión: `WITHDRAWEXPIREUNFREEZE` y `CANCELALLUNFREEZEV2`

**Propiedad comprobada (solo esta):** los cambios de un `WITHDRAWEXPIREUNFREEZE` o un
`CANCELALLUNFREEZEV2` válido de Stake 2.0 que tiene éxito se descartan cuando revierte la ejecución
que contiene la operación, en tres formas:

- **Mismo marco:** la operación y el `REVERT` están en el mismo contrato.
- **Revierte el externo:** un contrato llamado ejecuta la operación y termina bien, y después revierte
  el llamador. Revierte toda la transacción.
- **Revierte el interno:** el llamado ejecuta la operación y después revierte; el llamador ve que la
  llamada falla, continúa y termina bien. La transacción **tiene éxito y se confirma**, así que
  cualquier fuga desde el marco interno quedaría persistida.

**Resultado:** `WithdrawAndCancelUnfreezeV2RevertTest` **pasa** (6 de 6, 0 fallos). En este escenario
el comportamiento observado es el correcto. No se ha encontrado ninguna discrepancia y no se afirma
ninguna vulnerabilidad ni gravedad.

Este informe no modifica los anteriores. Con él quedan probadas, en el mismo marco y en marcos
anidados, las seis operaciones de la familia, con una excepción que se detalla en la sección 8.

## 1. Qué hace cada operación

- **`WITHDRAWEXPIREUNFREEZE`** paga todos los desbloqueos pendientes **ya vencidos** al saldo
  disponible y conserva los no vencidos. Toca solo la cuenta (saldo y lista de pendientes). Su
  resultado es la **cantidad retirada**.
- **`CANCELALLUNFREEZEV2`** también paga los vencidos al saldo, pero **devuelve cada pendiente no
  vencido al saldo congelado de su recurso** (y sube el peso global de ese recurso) y vacía la lista.
  Su resultado es un indicador de éxito.

Ninguna liquida recompensas ni toca votos o `DelegationStore`; esto es por lectura de los
procesadores, la prueba no lo comprueba.

## 2. Versión y configuración

| Dato | Valor |
|---|---|
| Origen | https://github.com/tronprotocol/java-tron (instantánea sin historial) |
| Commit upstream | `b33eed89a6a424c498d4fb1b03ca2c86eddf4840` (2026-09-11), `BLOCK_VERSION` 37 |
| Rama / commit de la prueba | `claude/pensive-mayer-ni6me2`, prueba en `3cc8e3c` (sin cambios posteriores) |
| Plataforma | Linux x86_64, OpenJDK 1.8.0_504 |
| Build | `./gradlew` (Gradle 7.6.4 fijado por el proyecto), `--no-daemon --max-workers=1` |
| Configuración de red de la prueba | `config-test.conf` + `--debug` (desactiva el límite de CPU de la VM, como `FreezeV2Test`) |

Configuración explícita: `allowTvmFreeze=1`, `allowNewResourceModel=1`, `allowDelegateResource=1`,
`unfreezeDelayDays=30`, `currentCycleNumber=5`, las banderas TVM de `FreezeV2Test` con
`AllowTvmFreezeV2=1` y `AllowTvmVote=1`, y `latestBlockHeaderTimestamp` fijado en T0 =
1 700 000 000 000 ms y avanzado durante la preparación.

## 3. Cobertura existente revisada

`FreezeV2Test#testFreezeV2Operations` ejercita retirar y cancelar con éxito, pero ninguna prueba
comprueba «la operación tiene éxito y después la ejecución revierte». No había una prueba equivalente
que reutilizar.

## 4. Entorno

Sin errores de entorno: 0 respuestas 429, compilación correcta, `BUILD SUCCESSFUL`.

## 5. Pruebas ejecutadas

Una sola invocación de Gradle, un intento, sin reintentos de pruebas, sobre el mismo commit:

| Clase | Pruebas | Resultado |
|---|---|---|
| `WithdrawAndCancelUnfreezeV2RevertTest` (nueva) | 6 | 6 pasan |
| `NestedUnfreezeV2RevertTest` (repetida) | 10 | 10 pasan |
| `UnfreezeV2RevertTest` (repetida) | 6 | 6 pasan |
| `NestedStakeV2RevertTest` (repetida) | 12 | 12 pasan |
| `FreezeV2RevertTest` (repetida) | 2 | 2 pasan |
| `DelegateResourceRevertTest` (repetida) | 2 | 2 pasan |
| `UnDelegateResourceRevertTest` (repetida) | 4 | 4 pasan |

Las seis pruebas nuevas pasaron a la primera; en esta ocasión no hubo ningún error de la prueba
durante el desarrollo.

## 6. La prueba

`framework/src/test/java/org/tron/common/runtime/vm/WithdrawAndCancelUnfreezeV2RevertTest.java`.

Dos contratos mínimos en bytecode TVM, con un ensamblador con etiquetas dentro de la prueba. STAKE
(el dueño del stake) congela, desbloquea, retira o cancela según calldata; tras retirar o cancelar,
**dentro de la misma ejecución y antes de terminar**, lee siete valores del estado en curso
(resultado de la operación, saldo congelado propio de ancho de banda, energía y poder de TRON, huecos
libres de pendientes, `BALANCE` y pendientes ya vencidos) y los devuelve con `RETURN` o `REVERT`.
CALLER llama a STAKE, reenvía esos datos más el indicador de éxito de la llamada, y termina con
`RETURN` o `REVERT`.

### Estado de partida

Construido con la propia VM avanzando la hora de cabecera, con tres recursos a la vez: el contrato
congela 10 TRX de ancho de banda, de energía y de poder de TRON; en T0 desbloquea A (ancho de banda,
3 TRX) y B (energía, 2 TRX); en T0 + 20 días desbloquea C (ancho de banda, 1 TRX) y D (poder de TRON,
4 TRX). La operación se ejecuta en T0 + 35 días, cuando **A y B han vencido** (T0 + 30 días) y **C y D
no** (T0 + 50 días). Estado previo comprobado: congelados `[6, 8, 6]` millones, lista de cuatro
entradas exacta, dos pendientes sin vencer, saldo disponible 1e17 − 30 TRX y pesos globales `[6, 8, 6]`.

### Cómo se demuestra que la operación ocurrió antes de la reversión

En la ejecución revertida el dato del `REVERT` contiene las siete palabras y la prueba exige que
coincidan con lo esperado. Valores observados (palabras 0–6) y el indicador de la llamada (palabra 7):

| Operación | Palabras 0–6 | Indicador: externo / interno |
|---|---|---|
| retirar | 5 000 000, 6 000 000, 8 000 000, 6 000 000, 30, saldo + 5 000 000, 0 | 1 / 0 |
| cancelar | 1, 7 000 000, 8 000 000, 10 000 000, 32, saldo + 5 000 000, 0 | 1 / 0 |

Las palabras del mismo marco y de los marcos anidados son idénticas, y coinciden con las del control.
Al retirar se ve que el vencido ya se cobró (saldo + 5 TRX, sin vencidos, y todavía dos pendientes,
que dejan 30 huecos); al cancelar, que C y D ya volvieron al congelado (`7` y `10` millones), que B no
se restauró porque estaba vencido y se pagó, y que la lista ya está vacía (32 huecos). En «revierte el
interno» la transacción termina en `SUCCESS` y en «revierte el externo» en `REVERT`; ambas cosas se
comprueban.

### Qué se compara

Antes de la ejecución revertida, después de ella y después de un control: **la cuenta del dueño del
stake** (saldo disponible, congelados V2 de los tres recursos, lista completa de pendientes con tipo,
importe y caducidad, y cuenta serializada completa), **la cuenta del contrato llamador** (saldo y
serializada completa) y **los pesos globales** (ancho de banda, energía, poder de TRON).

**Tras la reversión todo es idéntico al estado previo, en las seis pruebas.**

### Control (misma llamada, sin ninguna reversión, ejecutada después)

| Operación | Saldo disponible | Congelados (millones) | Pendientes | Pesos globales |
|---|---|---|---|---|
| retirar | +5 000 000 | `[6, 8, 6]` → `[6, 8, 6]` | 4 → 2 (quedan C y D) | `[6, 8, 6]` → `[6, 8, 6]` |
| cancelar | +5 000 000 | `[6, 8, 6]` → `[7, 8, 10]` | 4 → 0 | `[6, 8, 6]` → `[7, 8, 10]` |

En los controles se comprueba además el contenido exacto de la lista de pendientes que queda al
retirar y que la cuenta del contrato llamador no cambia.

### Costes que el protocolo permite conservar

Se conserva la energía consumida por la transacción, se confirme esta o no: el saldo del remitente
baja **exactamente** `receipt.getEnergyFee()` y `getEnergyUsageTotal() > 0`, en la ejecución revertida
y en el control. Es la única diferencia de saldos permitida. El cobro de los vencidos no es un coste
conservable y desaparece con la reversión. El ancho de banda de la transacción lo cobra el procesador
de bloques, que este banco de pruebas no ejecuta.

## 7. Qué demuestra el resultado y qué no

- **La operación se ejecutó de verdad antes de revertir, en los tres marcos.** Las lecturas en curso
  coinciden con lo esperado en las seis pruebas.
- **Las formas de reversión se distinguen** por el indicador de la llamada y por el resultado de la
  transacción.
- **Cada comparador puede detectar una fuga, al menos en una operación:** el saldo disponible y la
  lista de pendientes cambian en ambos controles, y los congelados y los pesos globales cambian en el
  de cancelar. Retirar no cambia congelados ni pesos, así que ahí esas comparaciones no prueban que
  detecten una fuga.
- **Sensibilidad no demostrada:** la cuenta del contrato llamador nunca cambia en ningún brazo.
- **Sin prueba de mutación** sobre el código de la VM ni de los procesadores.

## 8. Conclusión limitada a este escenario

En el commit `b33eed8`, con esta configuración de pruebas unitarias y en una plataforma (Linux
x86_64, Java 8), un `WITHDRAWEXPIREUNFREEZE` o un `CANCELALLUNFREEZEV2` válido, con desbloqueos
vencidos y pendientes de ancho de banda, energía y poder de TRON, **no deja cambios** en la cuenta del
dueño del stake, en la del llamador ni en los pesos globales cuando revierte el marco que contiene la
operación, tanto en el mismo marco como con un contrato llamado, y tanto si revierte el llamador tras
un llamado que terminó bien como si revierte el propio llamado con el llamador continuando y
confirmando la transacción. Solo se conserva la comisión de energía.

Clasificación: **comportamiento correcto**. No hay discrepancia del código ni problema de la prueba
que se conozca (ver las limitaciones de la sección 7).

**Excepción en la cobertura de la familia:** para `FREEZEBALANCEV2` están probados el mismo marco y
«revierte el externo», pero **no** «revierte el interno».

## 9. Lo que esta prueba NO cubre

- Retirar sin ningún desbloqueo vencido (la operación devuelve 0 y no escribe nada), y cancelar con
  solo pendientes o solo vencidos.
- Cuentas con votos o recompensas pendientes (estas dos operaciones no las tocan, por lectura del
  código).
- Más de dos niveles de anidamiento, y que el marco externo conserve sus **propios** cambios de
  Stake mientras se descarta el interno (solo se comprueba que no se conserve de más).
- `DELEGATECALL`, `CALLCODE` y `STATICCALL`; el límite de 32 pendientes; la ruta de delegaciones
  inválidas (`hasInvalidDelegatedV2`); desbloqueos de poder de TRON con delegados presentes.
- Otras causas de fallo distintas de `REVERT` (`OUT_OF_ENERGY`, `OUT_OF_TIME`, excepciones de VM).
- El registro de transacciones internas y de eventos, incluido el detalle que
  `CANCELALLUNFREEZEV2` guarda con `saveCancelAllUnfreezeV2Details` (no es estado persistido).
- El procesamiento real de bloques, el cobro de ancho de banda y la red.
- Un solo commit y una sola plataforma.

## 10. Cómo reproducirlo

```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 PATH=$JAVA_HOME/bin:$PATH
cd java-tron
./gradlew --no-daemon --max-workers=1 :framework:test \
  --tests "org.tron.common.runtime.vm.WithdrawAndCancelUnfreezeV2RevertTest"
```
