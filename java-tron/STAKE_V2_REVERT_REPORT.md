# Stake 2.0 y reversión: informe de la comprobación

**Propiedad comprobada (solo esta):** los cambios de una operación Stake 2.0 se descartan cuando
revierte la ejecución que la contiene.

**Resultado:** la prueba `FreezeV2RevertTest` **pasa** (2 de 2, 0 fallos) sobre el commit indicado.
En este escenario el comportamiento observado es el correcto. No se ha encontrado ninguna
discrepancia y no se afirma ninguna vulnerabilidad ni gravedad.

## 1. Versión registrada

| Dato | Valor |
|---|---|
| Origen | https://github.com/tronprotocol/java-tron (instantánea sin historial) |
| Commit | `b33eed89a6a424c498d4fb1b03ca2c86eddf4840` (2026-09-11), "fix(api): correct node info and network metric mappings (#6930)" |
| `BLOCK_VERSION` | 37 |
| Etiquetas | ninguna (clon superficial, `git describe` sin resultado) |
| Rama de trabajo | `claude/pensive-mayer-ni6me2` |

**Resultados anteriores:** no hay notas ni resultados previos en el repositorio sobre este
escenario. Las pruebas existentes (`FreezeV2Test`) solo provocan `REVERT` con argumentos inválidos
(recurso 3, importe 0, negativo, menor de 1 TRX, saldo entero), es decir, cuando la operación Stake
**falla**. Ninguna cubre "la operación tiene éxito y luego revierte la ejecución que la contiene",
así que hizo falta una prueba nueva.

## 2. Entorno y fallos del entorno (anotados antes de interpretar resultados)

Configuración usada:

- Linux x86_64, **OpenJDK 1.8.0_504** (instalado con `apt`; el build exige Java 8 en x86_64).
- **Gradle 7.6.4** mediante `./gradlew` (versión fijada por el proyecto), `--no-daemon --max-workers=1`.
- Prueba ejecutada con `:framework:test --tests <clase>`. Configuración de la prueba:
  `config-test.conf`, argumento `--debug` (igual que `FreezeV2Test`) y las mismas banderas TVM que
  `FreezeV2Test` (`allowTvmFreeze`, `allowNewResourceModel`, `allowDelegateResource`,
  `AllowTvmFreezeV2`, `AllowTvmVote`, `unfreezeDelayDays=30`). El motor de base de datos es el que
  fija `config-test.conf`; el build solo fuerza RocksDB en arm64 y en la tarea `testWithRocksDb`.

Fallos del entorno, todos previos a cualquier resultado sobre Stake 2.0:

| # | Síntoma | Causa | Acción |
|---|---|---|---|
| 1 | El build abortaba | Solo había Java 21 y el proyecto exige Java 8 | Instalé `openjdk-8-jdk-headless` (primero hizo falta `apt-get update`: el índice daba 404) |
| 2 | `Could not set unknown property 'classifier' for task ':actuator:sourcesJar'` | Usé el Gradle 8.14.3 del sistema; el proyecto fija 7.6.4 | Uso `./gradlew` |
| 3 | `429 Too Many Requests` de Maven Central (`jansi`, `reflections`) | Límite de tasa, transitorio | Reintentos con `--max-workers=1`; la caché de Gradle se fue llenando y las ejecuciones posteriores resolvieron todo |
| 4 | `403` de jitpack.io y `401` de repo.spring.io | Denegación por política de la organización / repositorio de respaldo | No lo rodeé; no fue necesario para compilar |

## 3. Línea base (pruebas existentes)

| Clase | Pruebas | Resultado |
|---|---|---|
| `org.tron.common.runtime.vm.FreezeV2Test` | 8 | 8 pasan |
| `org.tron.core.vm.nativecontract.StakeV2AfterSelfDestructTest` | 3 | 3 pasan |

## 4. La prueba

Fichero: `framework/src/test/java/org/tron/common/runtime/vm/FreezeV2RevertTest.java`.

Dos contratos mínimos escritos a mano en bytecode TVM (no hace falta `solc`). La operación es
`FREEZEBALANCEV2` (opcode `0xda`; saca de la pila primero el recurso y luego el importe).

- **STAKE** — calldata `importe | recurso | modo`. Ejecuta la operación, guarda su resultado
  `ok` en memoria; modo 0 devuelve `ok`, modo distinto de 0 hace `REVERT` con `ok` como dato.
- **CALLER** — calldata `destino | importe | recurso | modo`. Llama a STAKE (modo 0) y luego
  devuelve o revierte con el resultado de la llamada.

El dato del `REVERT` igual a 1 demuestra que la operación Stake **tuvo éxito** antes de revertir,
de modo que la prueba no se puede pasar "por casualidad" con una operación fallida.

Escenarios (cada uno para los tres recursos: ancho de banda, energía y poder de TRON; 5 TRX):

1. **Mismo marco** — STAKE congela y revierte en la misma ejecución.
2. **Anidado** — STAKE (llamado) congela y devuelve con normalidad; CALLER revierte después.

Cada escenario compara una **ejecución revertida** con una **ejecución de control** sin revert,
desde el mismo estado inicial y con los mismos argumentos. El control demuestra que las magnitudes
observadas sí cambian cuando no hay revert; sin él, "no cambió" no significaría nada.

### Qué se comprueba

Tras la ejecución revertida, idéntico al estado previo:

- saldo disponible del contrato;
- saldo congelado V2 de los tres recursos;
- contadores globales de peso (`TotalNetWeight`, `TotalEnergyWeight`, `TotalTronPowerWeight`);
- la cuenta del contrato serializada completa (byte a byte).

En el control: el saldo disponible baja exactamente el importe congelado, sube solo el saldo
congelado del recurso probado y su contador global sube `importe / TRX_PRECISION`.

### Lo que el protocolo permite conservar

El contrato se despliega con `consumeUserResourcePercent = 100` para que pague el llamador y no el
propietario. En una ejecución revertida se conserva la energía consumida: el saldo del llamador baja
**exactamente** `receipt.getEnergyFee()` y `getEnergyUsageTotal() > 0`. Esa es la única diferencia
de saldos permitida y se comprueba explícitamente; no se atribuye nada de ella al contrato.

## 5. Historial de ejecuciones (incluye mis fallos)

| Ejecución | Resultado | Diagnóstico |
|---|---|---|
| 1 | 12 intentos, todos fallan en `deploy()` | **Problema de la prueba.** (a) `existing contract address`: el mismo bytecode desplegado dos veces da el mismo `txid`/dirección; (b) `OUT_OF_TIME` por falta de `--debug`. Ocurrió antes de cualquier aserción sobre Stake. |
| 2 | Mismo marco: **pasa**. Anidado: falla | **Problema de la prueba.** `control caller: global weight counter expected 0 but was 5`: en el control el llamado congela legítimamente, así que el contador global sube; yo lo comparaba contra la cuenta del llamador. Por el orden de las aserciones, las anteriores del mismo test ya habían pasado para `res=0`; el fallo detuvo el resto, así que esa ejecución no valida los otros recursos del test anidado. |
| 3 (final) | **2 de 2 pasan**, 0 fallos | Corregido el diseño de la aserción. |

(El XML de las ejecuciones 1 y 2 cuenta varios intentos por prueba porque el proyecto usa el plugin
`test-retry`.)

## 6. Conclusión limitada a este escenario

En el commit `b33eed8`, con esta configuración de pruebas unitarias, la operación `FREEZEBALANCEV2`
ejecutada dentro de una ejecución que después revierte —tanto en el mismo marco como en un
contrato llamado cuyo llamador revierte— **no deja cambios** en el saldo disponible, el saldo
congelado V2, los contadores globales de peso ni la cuenta del contrato. Lo único que se conserva
es la comisión de energía del llamador, y coincide con la del recibo.

Clasificación del resultado: **comportamiento correcto**. No hay discrepancia del código.

### Lo que esta prueba NO cubre

- Otras operaciones Stake 2.0 (`UNFREEZEBALANCEV2`, delegar/quitar delegación, retirar,
  cancelar): no se han probado.
- Otras causas de fallo (`OUT_OF_ENERGY`, `OUT_OF_TIME`, excepciones de la VM); solo `REVERT`.
- Reversión en marcos más profundos o con varias operaciones Stake en la misma transacción.
- Votos, recompensas y la cuenta de destino de la comisión quemada.
- El procesamiento real de bloques y la red: la prueba usa `TransactionTrace` + `RuntimeImpl`
  directamente y no pasa por el procesador de ancho de banda.
- No se hizo prueba de mutación sobre el código de la VM: la sensibilidad de la prueba se apoya en
  el control sin revert, no en haber roto el código a propósito.
- Un solo commit y una sola plataforma (Linux x86_64, Java 8).

## 7. Cómo reproducirlo

```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 PATH=$JAVA_HOME/bin:$PATH
cd java-tron
./gradlew --no-daemon --max-workers=1 :framework:test \
  --tests "org.tron.common.runtime.vm.FreezeV2RevertTest"
```
