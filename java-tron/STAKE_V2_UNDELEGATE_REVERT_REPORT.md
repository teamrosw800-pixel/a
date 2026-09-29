# Stake 2.0 y reversión: `UNDELEGATERESOURCE`

**Propiedad comprobada (solo esta):** los cambios de un `UNDELEGATERESOURCE` válido de Stake 2.0
que tiene éxito se descartan cuando la ejecución que lo contiene revierte después.

**Resultado:** `UnDelegateResourceRevertTest` **pasa** (4 de 4, 0 fallos): ancho de banda y energía,
cada uno con deshacer parcial y deshacer total. En este escenario el comportamiento observado es el
correcto. No se ha encontrado ninguna discrepancia y no se afirma ninguna vulnerabilidad ni
gravedad.

Este informe no modifica los de `FREEZEBALANCEV2` (`STAKE_V2_REVERT_REPORT.md`) ni de
`DELEGATERESOURCE` (`STAKE_V2_DELEGATE_REVERT_REPORT.md`); sus conclusiones siguen limitadas a
esas operaciones y a un `REVERT` explícito del mismo marco.

## 1. Versión y configuración

| Dato | Valor |
|---|---|
| Origen | https://github.com/tronprotocol/java-tron (instantánea sin historial) |
| Commit upstream | `b33eed89a6a424c498d4fb1b03ca2c86eddf4840` (2026-09-11), `BLOCK_VERSION` 37 |
| Rama / commit de la prueba | `claude/pensive-mayer-ni6me2`, prueba en `582a3ec` (sin cambios posteriores) |
| Plataforma | Linux x86_64, OpenJDK 1.8.0_504 |
| Build | `./gradlew` (Gradle 7.6.4 fijado por el proyecto), `--no-daemon --max-workers=1` |
| Configuración de red de la prueba | `config-test.conf` + `--debug` (desactiva el límite de CPU de la VM, como `FreezeV2Test`) |

Configuración explícita de la prueba, igual que en la de `DELEGATERESOURCE`: `allowTvmFreeze=1`,
`allowNewResourceModel=1`, `allowDelegateResource=1`, `unfreezeDelayDays=30`, las banderas TVM de
`FreezeV2Test` con `AllowTvmFreezeV2=1`, y `latestBlockHeaderTimestamp = 1_700_000_000_000`
(ranura de cabecera 566 666 666).

Estado de partida, construido con la propia VM y con valores explícitos:

- El contrato (origen) congela 10 TRX de ancho de banda y 10 TRX de energía y delega 4 TRX del
  recurso probado a una cuenta normal (destino). Se deshace 1 TRX (parcial) o 4 TRX (total).
- Al destino se le siembra **uso real de recursos**: 1 000 000 del recurso probado, con
  `latestConsumeTime` 1 000 ranuras anterior a la cabecera. Así el traslado de uso y la
  actualización de esa marca de tiempo se ejecutan de verdad y son visibles.

## 2. Cobertura existente revisada

`FreezeV2Test#testDelegateResourceOperations` solo produce `REVERT` cuando deshacer **falla** la
validación. Además su destino nunca tiene uso de recursos, así que el traslado de uso de
`UnDelegateResourceProcessor` no se ejercita: no había una prueba equivalente que reutilizar.
`UnDelegateResourceActuatorTest` cubre la ruta sin VM (transacción nativa), no una ejecución de
contrato que revierte.

## 3. Entorno

Sin errores de entorno. El entorno ya estaba preparado (JDK 8, Gradle 7.6.4 vía `./gradlew`, caché
de dependencias poblada): 0 respuestas 429 y compilación correcta. Los fallos de entorno de la
primera comprobación están en `STAKE_V2_REVERT_REPORT.md`.

## 4. Pruebas ejecutadas

Una sola invocación de Gradle, un intento (`BUILD SUCCESSFUL`), sin reintentos de pruebas:

| Clase | Pruebas | Resultado |
|---|---|---|
| `org.tron.common.runtime.vm.UnDelegateResourceRevertTest` (nueva) | 4 | 4 pasan |
| `org.tron.common.runtime.vm.FreezeV2Test` (existente) | 8 | 8 pasan |
| `org.tron.core.actuator.UnDelegateResourceActuatorTest` (existente, sin VM) | 16 | 16 pasan |

Las pruebas nuevas pasaron en la primera ejecución; la sección 6 explica por qué ese resultado no
es vacío.

## 5. La prueba

`framework/src/test/java/org/tron/common/runtime/vm/UnDelegateResourceRevertTest.java`.

Un contrato mínimo en bytecode TVM, con un ensamblador con etiquetas dentro de la prueba, controlado
por calldata: `op 0` congela, `op 1` delega, `op 2` deshace la delegación (la operación bajo prueba).
En `op 2`, **dentro de la misma ejecución y antes de terminar**, el contrato lee cinco valores del
estado en curso con los precompilados de consulta de Stake 2.0 y los devuelve con `RETURN` o con
`REVERT` según un parámetro:

| Palabra | Contenido | Esperado (parcial / total) |
|---|---|---|
| 0 | resultado de `UNDELEGATERESOURCE` | 1 |
| 1 | aún delegado al destino (`0x01000010`) | 3 000 000 / 0 |
| 2 | total delegado por el origen (`0x01000014`) | 3 000 000 / 0 |
| 3 | total adquirido por el destino (`0x01000015`) | 3 000 000 / 0 |
| 4 | saldo congelado V2 propio del origen (`0x01000010`) | 7 000 000 / 10 000 000 |

### Cómo se demuestra que deshacer ocurrió antes de la reversión

En la ejecución revertida, el dato del `REVERT` contiene exactamente esos valores (comprobado en las
cuatro pruebas; los valores observados coinciden con la tabla). Antes de la operación, la prueba
comprueba que valían 4 000 000, 4 000 000, 4 000 000 y 6 000 000. Es decir, dentro de la VM el
cambio ya era visible antes de revertir. Además el recibo es `REVERT` y el tiempo de ejecución está
marcado como revertido.

### Qué se compara

Estado antes de la ejecución revertida, después de ella y después de un control:

- **Cuenta de origen y de destino:** saldo disponible, poder de TRON congelado, saldo congelado V2,
  delegado V2, adquirido V2, **uso de recursos**, **última marca de consumo** y la cuenta
  serializada completa.
- **Registro de delegación**, con la clave sin bloqueo y con la de bloqueo.
- **Las dos entradas de índice** (`V2_FROM_PREFIX + origen + destino` y
  `V2_TO_PREFIX + destino + origen`) y las **vistas de la API** (`getV2Index`) en ambas direcciones.
- **Pesos globales.**

**Tras la reversión todo es idéntico al estado previo**, en los cuatro escenarios. En el caso total
esto incluye que **las dos entradas de índice siguen presentes**, aunque el procesador las marca para
borrado cuando la delegación queda a cero.

### Control (misma llamada, sin reversión, ejecutada después)

Cambian exactamente estos campos, y solo estos:

- origen: recupera lo deshecho como congelado V2 y lo pierde como delegado V2; su uso del recurso
  **crece**; el saldo disponible no cambia;
- destino: su adquirido V2 baja lo deshecho; su uso del recurso **baja**; su última marca de consumo
  pasa a la ranura de cabecera; el otro recurso no se toca;
- registro de delegación: queda con el saldo reducido (parcial) o a cero (total);
- índices: en el caso parcial no se reescriben; en el caso total desaparecen ambas entradas y las
  vistas de la API quedan vacías;
- pesos globales: no cambian.

Valores observados en el control (impresos por la prueba; no son aserciones):

| Escenario | Uso destino | Uso origen | Registro tras el control | Índices tras el control |
|---|---|---|---|---|
| ancho de banda, parcial | 1 000 000 → 723 958 | 0 → 241 319 | bw=3 000 000, en=0 | presentes |
| energía, parcial | 1 000 000 → 723 958 | 0 → 241 319 | bw=0, en=3 000 000 | presentes |
| ancho de banda, total | 1 000 000 → 0 | 0 → 965 277 | bw=0, en=0 | borrados |
| energía, total | 1 000 000 → 0 | 0 → 965 277 | bw=0, en=0 | borrados |

Los números son coherentes con lo esperable: el uso del destino decae de 1 000 000 a 965 277 por
las 1 000 ranuras de antigüedad, y en el caso parcial se traslada la cuarta parte (241 319). Es una
comprobación de coherencia; la prueba solo exige la dirección del cambio, para no reimplementar la
fórmula del código.

### Costes que el protocolo permite conservar

En una ejecución revertida se conserva la energía consumida: el saldo del llamador baja
**exactamente** `receipt.getEnergyFee()` y `getEnergyUsageTotal() > 0`, tanto en la ejecución
revertida como en el control. Es la única diferencia de saldos permitida. El traslado de uso entre
destino y origen **no** es un coste conservable: es cambio de estado y debe desaparecer con la
reversión, y así ocurre (uso y última marca de consumo idénticos). El ancho de banda de la
transacción lo cobra el procesador de bloques, que este banco de pruebas no ejecuta.

## 6. Qué demuestra el resultado y qué no

Pasó a la primera. Esto respalda que las comprobaciones tienen efecto:

- **Se ejecutó de verdad antes de revertir.** Las cinco lecturas en curso cambian respecto al estado
  de partida y coinciden con lo esperado en las cuatro pruebas.
- **La ruta difícil se ejercita.** Con uso sembrado, el control muestra traslado de uso real y
  actualización de la marca de consumo, y en el caso total el borrado real de las dos entradas de
  índice. Si la reversión hubiera dejado algo de eso, la comparación tras la reversión habría
  fallado.
- **Sensibilidad demostrada por el control:** cuentas (saldos, uso, marca de consumo), registro de
  delegación, y, **solo en los casos totales**, entradas de índice y vistas de la API.
- **Sensibilidad no demostrada:** en los casos parciales los índices no cambian en el control, así
  que ahí su comparación no prueba que detecte una fuga; tampoco la clave de bloqueo del registro,
  las listas de entrada del origen y de salida del destino, el poder de TRON ni los pesos globales.
  Se comparan igualmente.
- **Sin prueba de mutación** sobre el código de la VM ni de los procesadores.

## 7. Conclusión limitada a este escenario

En el commit `b33eed8`, con esta configuración de pruebas unitarias y en una plataforma (Linux
x86_64, Java 8), un `UNDELEGATERESOURCE` válido de ancho de banda o de energía, parcial o total, con
el destino teniendo uso real de recursos y seguido por un `REVERT` explícito del mismo marco de
ejecución, **no deja cambios** en las cuentas de origen y de destino (incluidos uso y marca de
consumo), en el registro de delegación, en las dos entradas de índice ni en sus vistas de la API. Solo
se conserva la comisión de energía del llamador, que coincide con la del recibo.

Clasificación: **comportamiento correcto**. No hay discrepancia del código ni problema de la prueba
que se conozca (ver las limitaciones de la sección 6).

## 8. Observaciones al margen (no forman parte de la conclusión)

Son hechos observados mientras se probaba; no se han evaluado y no se sacan conclusiones:

- Tras deshacer del todo una delegación, el control muestra que el registro de delegación **permanece
  con saldos a cero** mientras que las dos entradas de índice **se borran**. Es lo que el código
  hace y no afecta a la propiedad probada; no se ha evaluado si es intencionado.
- En el ayudante `unDelegateResource` de `FreezeV2Test`, la aserción de uso del destino resta el uso
  trasladado en ancho de banda pero lo suma en energía. Esa diferencia no se manifiesta porque el uso
  del destino es 0 en esa prueba. En este trabajo, con uso sembrado, el uso de energía del destino
  **bajó** (1 000 000 → 723 958). No se ejecutó ese ayudante con uso distinto de cero, así que no se
  afirma que fallaría; es una observación sobre la prueba existente, no sobre el código.

## 9. Lo que esta prueba NO cubre

- Otras operaciones de Stake 2.0 (`UNFREEZEBALANCEV2`, retirar, cancelar).
- Reversión por un llamador distinto del marco que ejecutó la operación, y marcos más profundos.
- Otras causas de fallo distintas de `REVERT` (`OUT_OF_ENERGY`, `OUT_OF_TIME`, excepciones de VM).
- Las ramas del procesador en que el adquirido del destino es menor que lo deshecho (destino
  recreado tras un `SELFDESTRUCT`) o en que la cuenta destino no existe.
- Deshacer y delegar en la misma transacción, varias operaciones en una transacción, y delegaciones
  con bloqueo o caducidad.
- El registro de transacciones internas y de eventos de la ejecución revertida (no es estado
  persistido).
- El procesamiento real de bloques, el cobro de ancho de banda y la red.
- Un solo commit y una sola plataforma.

## 10. Cómo reproducirlo

```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 PATH=$JAVA_HOME/bin:$PATH
cd java-tron
./gradlew --no-daemon --max-workers=1 :framework:test \
  --tests "org.tron.common.runtime.vm.UnDelegateResourceRevertTest"
```
