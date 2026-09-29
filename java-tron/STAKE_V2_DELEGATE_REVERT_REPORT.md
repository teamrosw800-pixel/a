# Stake 2.0 y reversión: `DELEGATERESOURCE`

**Propiedad comprobada (solo esta):** los cambios de una delegación válida de Stake 2.0
(`DELEGATERESOURCE`) que tiene éxito se descartan cuando la ejecución que la contiene revierte
después.

**Resultado:** `DelegateResourceRevertTest` **pasa** (2 de 2, 0 fallos) para ancho de banda y para
energía. En este escenario el comportamiento observado es el correcto. No se ha encontrado ninguna
discrepancia y no se afirma ninguna vulnerabilidad ni gravedad.

Este informe no modifica el de `FREEZEBALANCEV2` (`STAKE_V2_REVERT_REPORT.md`), cuya conclusión
sigue limitada a esa operación y a un `REVERT` explícito.

## 1. Versión y configuración

| Dato | Valor |
|---|---|
| Origen | https://github.com/tronprotocol/java-tron (instantánea sin historial) |
| Commit upstream | `b33eed89a6a424c498d4fb1b03ca2c86eddf4840` (2026-09-11), `BLOCK_VERSION` 37 |
| Rama / commit de la prueba | `claude/pensive-mayer-ni6me2`, prueba en `d1687b8` (sin cambios posteriores) |
| Plataforma | Linux x86_64, OpenJDK 1.8.0_504 |
| Build | `./gradlew` (Gradle 7.6.4 fijado por el proyecto), `--no-daemon --max-workers=1` |
| Configuración de red de la prueba | `config-test.conf` + argumento `--debug` (desactiva el límite de tiempo de CPU de la VM, como `FreezeV2Test`) |

Configuración explícita de la prueba (`afterInit`):

- `allowTvmFreeze=1`, `allowNewResourceModel=1`, `allowDelegateResource=1`, `unfreezeDelayDays=30`.
- `VMConfig`: hard fork VM, TRC-10, Constantinople, Solidity 0.5.9, Istanbul, `AllowTvmFreezeV2=1`,
  `AllowTvmVote=1`.
- `latestBlockHeaderTimestamp = 1_700_000_000_000` (fijo, para que las marcas de tiempo de los
  índices sean deterministas).
- Origen de la delegación: un contrato desplegado con `consumeUserResourcePercent = 100` (paga el
  llamador) y saldo de 10^17 SUN. Destino: una cuenta normal (no contrato) creada antes.
- Estado de partida válido, construido con la propia VM: el contrato congela 10 TRX en ancho de
  banda y 10 TRX en energía. Se delega 4 TRX. El destino no tenía ninguna delegación previa.

## 2. Cobertura existente revisada

`FreezeV2Test#testDelegateResourceOperations` solo produce `REVERT` cuando la delegación **falla**
la validación (destino inexistente, importe 0, importe mayor o menor que lo congelado, recurso 2 o
3, destino igual al origen, destino contrato). Ninguna prueba existente cubre «la delegación tiene
éxito y después la ejecución revierte». No había una prueba equivalente que reutilizar, así que se
añadió una.

## 3. Entorno

No hubo errores de entorno en esta ejecución. El entorno ya estaba preparado de la comprobación
anterior (JDK 8 instalado, Gradle 7.6.4 vía `./gradlew`, caché de dependencias completa): 0
respuestas 429 en el registro de este intento y la compilación de las pruebas terminó bien. Los
fallos de entorno de la sesión anterior están documentados en `STAKE_V2_REVERT_REPORT.md`.

## 4. Pruebas ejecutadas

Una sola invocación de Gradle, un intento (`BUILD SUCCESSFUL`), sin reintentos de pruebas:

| Clase | Pruebas | Resultado |
|---|---|---|
| `org.tron.common.runtime.vm.DelegateResourceRevertTest` (nueva) | 2 | 2 pasan |
| `org.tron.common.runtime.vm.FreezeV2Test` (existente) | 8 | 8 pasan |
| `org.tron.core.actuator.DelegateResourceActuatorTest` (existente, ruta sin VM) | 24 | 24 pasan |

Las pruebas nuevas pasaron en la primera ejecución. Por eso el informe explica abajo por qué ese
resultado no es vacío.

## 5. La prueba

`framework/src/test/java/org/tron/common/runtime/vm/DelegateResourceRevertTest.java`.

Un contrato mínimo escrito a mano en bytecode TVM (con un ensamblador con etiquetas dentro de la
prueba; no hace falta `solc`), controlado por calldata:

- `op 0`: `FREEZEBALANCEV2(importe, recurso)`, solo para construir el estado de partida.
- `op 1`: `DELEGATERESOURCE(destino, importe, recurso)` (la operación bajo prueba) y, **dentro de la
  misma ejecución y antes de terminar**, tres consultas a los precompilados de Stake 2.0, que leen
  el estado en curso y no el persistido:
  - `0x01000010(destino, este contrato, recurso)`: cuánto ha delegado este contrato al destino;
  - `0x01000014(este contrato, recurso)`: total delegado por este contrato;
  - `0x01000015(destino, recurso)`: total adquirido por el destino.
  El contrato devuelve esos cuatro valores (`ok` y las tres consultas), con `RETURN` o con
  `REVERT` según un parámetro.

### Cómo se demuestra que la delegación ocurrió antes de la reversión

En la ejecución revertida, el dato del `REVERT` contiene `ok = 1` y las tres lecturas en curso, y
la prueba exige que las tres sean iguales a los 4 TRX delegados. Es decir: dentro de la VM la
delegación ya era visible (registro de delegación, total delegado del origen y total adquirido del
destino) antes de revertir. Además se comprueba que el recibo es `REVERT` y que el tiempo de
ejecución está marcado como revertido.

### Qué se compara

Estado tomado antes de la ejecución revertida, después de ella y después de un control:

- **Cuenta de origen y de destino:** saldo disponible, saldo congelado V2 (ancho de banda y
  energía), saldo delegado V2, saldo adquirido V2, poder de TRON congelado y la cuenta serializada
  completa.
- **Registro de delegación** (`DelegatedResourceStore`), con la clave sin bloqueo y con la clave de
  bloqueo.
- **Los dos índices** que relacionan ambas cuentas: la entrada «desde» (`V2_FROM_PREFIX + origen +
  destino`) y la entrada «hasta» (`V2_TO_PREFIX + destino + origen`).
- **Vista de la API de índices** (`getV2Index`) de origen y destino, en ambas direcciones.
- **Pesos globales** (ancho de banda, energía, poder de TRON).

**Tras la reversión, todo lo anterior es idéntico al estado previo.** En el control (misma llamada,
mismos argumentos, sin reversión, ejecutada después de la revertida) cambian exactamente estos
campos, y solo estos:

- origen: congelado V2 baja 4 TRX y delegado V2 sube 4 TRX en el recurso probado; el saldo
  disponible no cambia (la delegación no mueve TRX);
- destino: adquirido V2 sube 4 TRX en el recurso probado;
- aparece el registro de delegación con 4 TRX en el recurso probado, 0 en el otro y sin caducidad;
- aparecen las dos entradas de índice, apuntando al destino y al origen, con la marca de tiempo
  fijada, y las vistas de la API muestran la relación;
- los pesos globales no cambian, como se espera: delegar traslada la titularidad de lo congelado,
  no cambia cuánto hay congelado en total.

El control además demuestra que la delegación se puede repetir con los mismos argumentos después de
la reversión: la ejecución revertida no consumió saldo congelado disponible ni dejó ningún registro
que lo impidiera.

### Costes que el protocolo permite conservar

En una ejecución revertida se conserva la energía consumida. La prueba exige que el saldo del
llamador baje **exactamente** `receipt.getEnergyFee()` y que `getEnergyUsageTotal() > 0`, tanto en la
ejecución revertida como en el control. Esa es la única diferencia de saldos permitida. El ancho de
banda de la transacción lo cobra el procesador de bloques, que este banco de pruebas no ejecuta;
por tanto no forma parte de la comparación.

## 6. Qué demuestra el resultado y qué no

El resultado pasó a la primera. Para no confundir «pasa» con «no comprueba nada», esto es lo que
respalda que las comprobaciones tienen efecto:

- **La operación se ejecutó de verdad antes de revertir.** Las tres lecturas en curso valen 4 TRX en
  la ejecución revertida; si la delegación no hubiera ocurrido, la prueba habría fallado en esas
  aserciones.
- **Los comparadores pueden detectar una fuga.** En el control cambian, con esas mismas lecturas y
  los mismos accesores, la cuenta de origen, la cuenta de destino (campo adquirido), el registro de
  delegación, las dos entradas de índice y las dos vistas de la API. Si la reversión hubiera dejado
  algo de eso, la comparación tras la reversión habría fallado.
- **Limitación de esa sensibilidad.** Para el resto no se ha visto cambiar nada en el control, así
  que no se ha demostrado que ese comparador detecte una fuga concreta: la clave de bloqueo del
  registro, las listas de entrada del origen y de salida del destino, el poder de TRON y los pesos
  globales. Se comparan igualmente, pero su capacidad de detección en este escenario no está
  probada. En la prueba de `FREEZEBALANCEV2` los pesos sí se movían en el control.
- **No se hizo prueba de mutación** sobre el código de la VM ni de los procesadores. La sensibilidad
  se apoya en el control, no en haber roto el código a propósito.

## 7. Conclusión limitada a este escenario

En el commit `b33eed8`, con esta configuración de pruebas unitarias y en una plataforma (Linux
x86_64, Java 8), una delegación válida de ancho de banda o de energía hecha con `DELEGATERESOURCE`
y seguida por un `REVERT` explícito del mismo marco de ejecución **no deja cambios** en las cuentas
de origen y de destino, en el registro de delegación, en las dos entradas de índice ni en sus
vistas de la API. Solo se conserva la comisión de energía del llamador, que coincide con la del
recibo.

Clasificación del resultado: **comportamiento correcto**. No hay discrepancia del código, ni
problema de la prueba que se conozca (ver las limitaciones de la sección 6).

### Lo que esta prueba NO cubre

- Otras operaciones de Stake 2.0 (`UNDELEGATERESOURCE`, `UNFREEZEBALANCEV2`, retirar, cancelar).
- Reversión por un llamador distinto del marco que delegó (delegar en un contrato llamado y que
  revierta el llamador), y marcos más profundos.
- Otras causas de fallo distintas de `REVERT` (`OUT_OF_ENERGY`, `OUT_OF_TIME`, excepciones de VM).
- Varias delegaciones en la misma transacción, delegar sobre una delegación previa existente, un
  destino que ya tenía recursos adquiridos, y delegaciones con bloqueo o caducidad.
- El registro de transacciones internas y de eventos de la ejecución revertida (por ejemplo la marca
  de rechazo de la transacción interna): no se compara, porque no es estado persistido.
- El procesamiento real de bloques, el cobro de ancho de banda y la red.
- Un solo commit y una sola plataforma.

## 8. Cómo reproducirlo

```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 PATH=$JAVA_HOME/bin:$PATH
cd java-tron
./gradlew --no-daemon --max-workers=1 :framework:test \
  --tests "org.tron.common.runtime.vm.DelegateResourceRevertTest"
```
