# Stake 2.0 y reversión en marcos anidados: `DELEGATERESOURCE` y `UNDELEGATERESOURCE`

**Propiedad comprobada (solo esta):** los cambios de un `DELEGATERESOURCE` o `UNDELEGATERESOURCE`
válido de Stake 2.0, ejecutado por un contrato **llamado**, se descartan cuando revierte el marco
de ejecución que contiene la operación, en dos formas:

- **Revierte el externo:** el llamado ejecuta la operación y termina bien (su marco se fusiona con
  el del llamador) y después revierte el llamador. Revierte toda la transacción.
- **Revierte el interno:** el llamado ejecuta la operación y después revierte; el llamador ve que
  la llamada falla, continúa y termina bien. La transacción **tiene éxito y se confirma**, así que
  cualquier fuga desde el marco interno quedaría persistida.

**Resultado:** `NestedStakeV2RevertTest` **pasa** (12 de 12, 0 fallos). En este escenario el
comportamiento observado es el correcto. No se ha encontrado ninguna discrepancia y no se afirma
ninguna vulnerabilidad ni gravedad.

Este informe no modifica los anteriores (`STAKE_V2_REVERT_REPORT.md`,
`STAKE_V2_DELEGATE_REVERT_REPORT.md`, `STAKE_V2_UNDELEGATE_REVERT_REPORT.md`), que siguen limitados
al mismo marco de ejecución. Cierra la laguna que esos informes listaban como «no cubierto» para
delegar y deshacer la delegación. Para `FREEZEBALANCEV2` el caso anidado ya estaba en su prueba.

## 1. Versión y configuración

| Dato | Valor |
|---|---|
| Origen | https://github.com/tronprotocol/java-tron (instantánea sin historial) |
| Commit upstream | `b33eed89a6a424c498d4fb1b03ca2c86eddf4840` (2026-09-11), `BLOCK_VERSION` 37 |
| Rama / commit de la prueba | `claude/pensive-mayer-ni6me2`, prueba en `f5e0cb6` (sin cambios posteriores) |
| Plataforma | Linux x86_64, OpenJDK 1.8.0_504 |
| Build | `./gradlew` (Gradle 7.6.4 fijado por el proyecto), `--no-daemon --max-workers=1` |
| Configuración de red de la prueba | `config-test.conf` + `--debug` (desactiva el límite de CPU de la VM, como `FreezeV2Test`) |

Configuración explícita de la prueba, igual que en las anteriores: `allowTvmFreeze=1`,
`allowNewResourceModel=1`, `allowDelegateResource=1`, `unfreezeDelayDays=30`, las banderas TVM de
`FreezeV2Test` con `AllowTvmFreezeV2=1` y `latestBlockHeaderTimestamp = 1_700_000_000_000`.

Estado de partida, construido con la propia VM mediante llamadas directas al contrato llamado:
congela 10 TRX de ancho de banda y 10 TRX de energía; en los casos de deshacer, delega 4 TRX del
recurso probado a una cuenta normal, cuyo uso de recursos se siembra en 1 000 000 con
`latestConsumeTime` 1 000 ranuras anterior a la cabecera (para que el traslado de uso y la marca de
tiempo se ejecuten de verdad). Se delega 4 TRX o se deshace 1 TRX (parcial) o 4 TRX (total).

## 2. Cobertura existente revisada

Salvo las pruebas de esta serie, solo `FreezeV2Test` ejercita estas operaciones a nivel de contrato,
y ninguna prueba previa usa una llamada anidada a un contrato con Stake 2.0 (búsqueda heurística por
nombre, no exhaustiva). Las pruebas del mismo marco de esta serie
(`DelegateResourceRevertTest`, `UnDelegateResourceRevertTest`) no se han modificado.

## 3. Entorno

Sin errores de entorno: 0 respuestas 429, compilación correcta, `BUILD SUCCESSFUL`. Los fallos de
entorno de la primera comprobación están en `STAKE_V2_REVERT_REPORT.md`.

## 4. Pruebas ejecutadas

Una sola invocación de Gradle, un intento, sin reintentos de pruebas, sobre el mismo commit:

| Clase | Pruebas | Resultado |
|---|---|---|
| `NestedStakeV2RevertTest` (nueva) | 12 | 12 pasan |
| `FreezeV2RevertTest` (repetida) | 2 | 2 pasan |
| `DelegateResourceRevertTest` (repetida) | 2 | 2 pasan |
| `UnDelegateResourceRevertTest` (repetida) | 4 | 4 pasan |

Las 12 pruebas nuevas son: delegar {ancho de banda, energía} y deshacer {ancho de banda, energía} ×
{parcial, total}, cada una con «revierte el externo» y «revierte el interno». Pasaron a la primera.

## 5. La prueba

`framework/src/test/java/org/tron/common/runtime/vm/NestedStakeV2RevertTest.java`.

Dos contratos mínimos en bytecode TVM, con un ensamblador con etiquetas dentro de la prueba:

- **STAKE (el llamado, dueño del stake):** según calldata congela, delega o deshace la delegación.
  Tras delegar o deshacer, y dentro de la misma ejecución, lee cuatro valores del estado en curso con
  los precompilados de consulta de Stake 2.0 (`0x01000010`, `0x01000014`, `0x01000015`) y los
  devuelve con `RETURN` o con `REVERT` según un parámetro.
- **CALLER:** llama a STAKE con esos datos, copia en su propia respuesta los datos que devolvió
  (la VM copia los datos de retorno también cuando el llamado revierte) más el indicador de éxito de
  la llamada, y termina con `RETURN` o `REVERT` según otro parámetro.

### Cómo se demuestra que la operación ocurrió antes de la reversión

Los datos que llegan hasta la transacción de nivel superior contienen el resultado de la operación
y las cuatro lecturas en curso; la prueba exige que coincidan con lo esperado en ambas formas:

| Caso | Palabras esperadas: resultado, delegado al destino, total delegado, total adquirido, congelado propio |
|---|---|
| delegar 4 TRX | 1, 4 000 000, 4 000 000, 4 000 000, 6 000 000 |
| deshacer 1 TRX (parcial) | 1, 3 000 000, 3 000 000, 3 000 000, 7 000 000 |
| deshacer 4 TRX (total) | 1, 0, 0, 0, 10 000 000 |

Además se comprueba el indicador de la llamada: 1 cuando revierte el externo (el llamado terminó
bien) y 0 cuando revierte el interno (la llamada falló); y que la transacción termina en `REVERT` en
el primer caso y en `SUCCESS` en el segundo.

### Qué se compara

Antes de la ejecución revertida, después de ella y después de un control:

- **Cuenta del llamado (dueño del stake), cuenta del contrato llamador y cuenta del destino:**
  saldo disponible, poder de TRON, congelado V2, delegado V2, adquirido V2, uso de recursos, última
  marca de consumo y cuenta serializada completa.
- **Registro de delegación** (clave sin bloqueo y de bloqueo), **las dos entradas de índice** y las
  **vistas de la API** en ambas direcciones, y los **pesos globales**.

**Tras la reversión todo es idéntico al estado previo, en las 12 pruebas.** En «revierte el interno»
esto tiene especial valor: la transacción se confirma, y aun así nada de lo que hizo el marco
interno queda persistido.

### Control (llamada anidada idéntica, sin ninguna reversión, ejecutada después)

La llamada anidada sí persiste el cambio: mueve congelado/delegado del llamado y adquirido del
destino en el recurso probado, deja el registro con el saldo esperado, y, según el caso, crea las dos
entradas de índice (delegar), las conserva (deshacer parcial) o las borra (deshacer total); en
deshacer, el uso baja en el destino y sube en el llamado y la marca de consumo pasa a la cabecera.
Las lecturas en curso del control son idénticas a las de la ejecución revertida. La cuenta del
contrato llamador y los pesos globales no cambian.

Los controles de esta prueba son más ligeros que los de las pruebas del mismo marco: comprueban los
campos principales y no repiten todos los campos de aquellas. La comparación tras la reversión, en
cambio, es el mismo conjunto completo.

### Costes que el protocolo permite conservar

Se conserva la energía consumida por la transacción, se confirme esta o no: el saldo del remitente
baja **exactamente** `receipt.getEnergyFee()` y `getEnergyUsageTotal() > 0`, en la ejecución
revertida y en el control. Es la única diferencia de saldos permitida. El traslado de uso entre
destino y llamado no es un coste conservable y desaparece con la reversión. El ancho de banda de la
transacción lo cobra el procesador de bloques, que este banco de pruebas no ejecuta.

## 6. Qué demuestra el resultado y qué no

Pasó a la primera. Esto respalda que las comprobaciones tienen efecto:

- **La operación se ejecutó de verdad antes de revertir, en el marco interno.** Las cuatro lecturas
  en curso coinciden con lo esperado en las 12 pruebas y llegan al nivel superior por los datos de
  retorno.
- **Las dos formas de reversión se distinguen.** El indicador de la llamada vale 1 o 0 según el
  caso y el resultado de la transacción es `REVERT` o `SUCCESS` según el caso.
- **Sensibilidad demostrada por el control:** cuentas del llamado y del destino (saldos, uso, marca
  de consumo), registro de delegación y, en delegar y en deshacer total, entradas de índice y vistas
  de la API (se crean o se borran).
- **Sensibilidad no demostrada:** en deshacer parcial los índices no cambian en el control; la
  cuenta del contrato llamador nunca cambia en ninguno de los brazos; tampoco la clave de bloqueo,
  las listas de entrada del llamado y de salida del destino, el poder de TRON ni los pesos globales.
  Se comparan igualmente, pero su capacidad de detección aquí no está probada.
- **Sin prueba de mutación** sobre el código de la VM ni de los procesadores.

## 7. Conclusión limitada a este escenario

En el commit `b33eed8`, con esta configuración de pruebas unitarias y en una plataforma (Linux
x86_64, Java 8), un `DELEGATERESOURCE` o `UNDELEGATERESOURCE` válido ejecutado por un contrato
llamado, de ancho de banda o de energía (y parcial o total al deshacer), **no deja cambios** en las
cuentas del llamado, del llamador y del destino, en el registro de delegación, en las dos entradas de
índice ni en sus vistas de la API cuando revierte el marco que contiene la operación, tanto si el
que revierte es el llamador tras un llamado que terminó bien como si es el propio llamado con el
llamador continuando y confirmando la transacción. Solo se conserva la comisión de energía.

Clasificación: **comportamiento correcto**. No hay discrepancia del código ni problema de la prueba
que se conozca (ver las limitaciones de la sección 6).

## 8. Lo que esta prueba NO cubre

- Otras operaciones de Stake 2.0 (`UNFREEZEBALANCEV2`, retirar, cancelar) en marcos anidados.
- Más de dos niveles de anidamiento.
- Que un marco externo tenga sus **propios** cambios de Stake que deban conservarse mientras el
  interno se descarta (es decir, que no se descarte de más). Solo se comprueba que no se conserve de
  más.
- `DELEGATECALL` y `CALLCODE`, donde el contexto y el dueño del stake cambian, y `STATICCALL`, donde
  la operación provoca una excepción distinta.
- Otras causas de fallo del marco interno distintas de `REVERT` (`OUT_OF_ENERGY`, `OUT_OF_TIME`,
  excepciones de VM).
- Las ramas del procesador en que el adquirido del destino es menor que lo deshecho (destino
  recreado tras un `SELFDESTRUCT`) o en que la cuenta destino no existe.
- El registro de transacciones internas y de eventos de la ejecución revertida (no es estado
  persistido).
- El procesamiento real de bloques, el cobro de ancho de banda y la red.
- Un solo commit y una sola plataforma.

## 9. Cómo reproducirlo

```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 PATH=$JAVA_HOME/bin:$PATH
cd java-tron
./gradlew --no-daemon --max-workers=1 :framework:test \
  --tests "org.tron.common.runtime.vm.NestedStakeV2RevertTest"
```
