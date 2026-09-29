# Stake 2.0 y reversión: resumen de la serie

**Propiedad comprobada en toda la serie (solo esta):** los cambios de una operación válida de
Stake 2.0 que tiene éxito se descartan cuando revierte la ejecución que la contiene.

**Resultado:** las **48 pruebas** de la serie pasan (0 fallos), ejecutadas juntas, en un solo intento,
sobre el mismo commit y sin ningún error de entorno. En este escenario el comportamiento observado es
el correcto para las seis operaciones y las tres formas de reversión. **No se ha encontrado ninguna
discrepancia del código, y no se afirma ninguna vulnerabilidad ni gravedad.**

Esto no es una evaluación de seguridad de java-tron ni prueba la ausencia de fallos: es una respuesta
acotada a una sola pregunta, con las limitaciones de la sección 5. Nada de aquí es un informe para
un programa de recompensas.

## 1. Versión y entorno

| Dato | Valor |
|---|---|
| Origen | https://github.com/tronprotocol/java-tron (instantánea sin historial) |
| Commit upstream | `b33eed89a6a424c498d4fb1b03ca2c86eddf4840` (2026-09-11), `BLOCK_VERSION` 37 |
| Rama / commit de las pruebas | `claude/pensive-mayer-ni6me2`, pruebas en `6621846` (los commits posteriores solo añaden documentos) |
| Plataforma | Linux x86_64, OpenJDK 1.8.0_504 |
| Build | `./gradlew` (Gradle 7.6.4 fijado por el proyecto), `--no-daemon --max-workers=1` |
| Configuración de red de las pruebas | `config-test.conf` + `--debug` (desactiva el límite de CPU de la VM, como `FreezeV2Test`) |

Las pruebas usan `TransactionTrace` y `RuntimeImpl` directamente. No procesan bloques, así que el
ancho de banda de la transacción no forma parte de ninguna comparación (lo cobra el procesador de
bloques).

## 2. Matriz de cobertura

Tres formas: **mismo marco** (la operación y el `REVERT` en el mismo contrato), **revierte el
externo** (un contrato llamado ejecuta la operación y termina bien; después revierte el llamador) y
**revierte el interno** (el llamado ejecuta la operación y revierte; el llamador continúa y la
transacción se confirma, así que una fuga quedaría persistida).

| Operación | Mismo marco | Revierte el externo | Revierte el interno | Informe |
|---|---|---|---|---|
| `FREEZEBALANCEV2` | `FreezeV2RevertTest` | `FreezeV2RevertTest`, `NestedFreezeV2RevertTest` | `NestedFreezeV2RevertTest` | `STAKE_V2_REVERT_REPORT.md` y sección 4 de este resumen |
| `DELEGATERESOURCE` | `DelegateResourceRevertTest` | `NestedStakeV2RevertTest` | `NestedStakeV2RevertTest` | `STAKE_V2_DELEGATE_REVERT_REPORT.md`, `STAKE_V2_NESTED_REVERT_REPORT.md` |
| `UNDELEGATERESOURCE` | `UnDelegateResourceRevertTest` | `NestedStakeV2RevertTest` | `NestedStakeV2RevertTest` | `STAKE_V2_UNDELEGATE_REVERT_REPORT.md`, `STAKE_V2_NESTED_REVERT_REPORT.md` |
| `UNFREEZEBALANCEV2` | `UnfreezeV2RevertTest` | `NestedUnfreezeV2RevertTest` | `NestedUnfreezeV2RevertTest` | `STAKE_V2_UNFREEZE_REVERT_REPORT.md`, `STAKE_V2_NESTED_UNFREEZE_REVERT_REPORT.md` |
| `WITHDRAWEXPIREUNFREEZE` | `WithdrawAndCancelUnfreezeV2RevertTest` | ídem | ídem | `STAKE_V2_WITHDRAW_CANCEL_REVERT_REPORT.md` |
| `CANCELALLUNFREEZEV2` | `WithdrawAndCancelUnfreezeV2RevertTest` | ídem | ídem | `STAKE_V2_WITHDRAW_CANCEL_REVERT_REPORT.md` |

`FreezeV2RevertTest` no tenía «revierte el interno»; se descubrió al revisar la matriz y se cerró con
`NestedFreezeV2RevertTest`.

## 3. Ejecución final

Una sola invocación de Gradle sobre las ocho clases, un intento, sin reintentos de pruebas, 0
respuestas 429, `BUILD SUCCESSFUL`:

| Clase | Pruebas | Resultado |
|---|---|---|
| `FreezeV2RevertTest` | 2 | pasan |
| `NestedFreezeV2RevertTest` | 6 | pasan |
| `DelegateResourceRevertTest` | 2 | pasan |
| `UnDelegateResourceRevertTest` | 4 | pasan |
| `NestedStakeV2RevertTest` | 12 | pasan |
| `UnfreezeV2RevertTest` | 6 | pasan |
| `NestedUnfreezeV2RevertTest` | 10 | pasan |
| `WithdrawAndCancelUnfreezeV2RevertTest` | 6 | pasan |
| **Total** | **48** | **48 pasan** |

Las pruebas existentes de estas operaciones también pasaron cuando se ejecutaron como línea base
(`FreezeV2Test` 8/8, `DelegateResourceActuatorTest` 24/24, `UnDelegateResourceActuatorTest` 16/16,
`UnfreezeBalanceV2ActuatorTest` 19/19, `StakeV2AfterSelfDestructTest` 3/3).

## 4. Cierre del hueco de `FREEZEBALANCEV2` («revierte el interno»)

`NestedFreezeV2RevertTest` prueba, para ancho de banda, energía y poder de TRON, un
`FREEZEBALANCEV2` válido ejecutado por un contrato llamado, en las dos formas anidadas (la del
externo se repite aquí en el mismo arnés que las demás operaciones). El congelado cambia el saldo
disponible, el saldo congelado del recurso, el poder de TRON antiguo (de 0 a −1 con el primer
congelado) y el peso global del recurso.

Valores observados (palabras devueltas: resultado, congelado propio del recurso, saldo disponible,
indicador de la llamada; 5 TRX):

| Forma | Palabras en la ejecución revertida | Control anidado |
|---|---|---|
| revierte el externo | 1, 5 000 000, saldo − 5 000 000, indicador 1 | igual |
| revierte el interno | 1, 5 000 000, saldo − 5 000 000, indicador 0 | igual, con indicador 1 |

Tras la reversión, en las seis pruebas, la cuenta del llamado, la del llamador y los pesos globales
son idénticos al estado previo (incluido el poder antiguo, que sigue en 0). En el control cambian
exactamente: el congelado del recurso (`0 → 5 000 000`), el saldo disponible (−5 000 000), el peso
global del recurso (`0 → 5`) y el poder antiguo (`0 → −1`). Solo se conserva la comisión de energía.
Pasaron a la primera.

## 5. Limitaciones comunes a toda la serie

- **Pasaron a la primera, con dos excepciones ya explicadas** (`FreezeV2RevertTest` y
  `UnfreezeV2RevertTest`, sección 6). Para que «pasa» no signifique «no comprueba nada», cada prueba
  incluye un control sin reversión y lecturas del estado en curso, dentro de la misma ejecución y
  antes de revertir, que demuestran que la operación ocurrió.
- **Sensibilidad «en algún escenario».** Cada comparador cambia en el control de al menos un escenario
  de su operación, pero no en todos; y algunos nunca cambian en ninguno (por ejemplo, la cuenta del
  contrato llamador, la clave de bloqueo del registro de delegación, las listas de entrada del dueño y
  de salida del destino). Se comparan igualmente, pero su capacidad de detección no está probada.
  Cada informe lo detalla para su operación.
- **Sin prueba de mutación** sobre el código de la VM ni de los procesadores: la sensibilidad se apoya
  en los controles, no en haber roto el código a propósito.
- **Estado sembrado.** Los votos, los ciclos y los índices de recompensa se escribieron directamente
  en los almacenes (como hace `FreezeV2Test`) en los escenarios que los usan, no con `VOTEWITNESS`
  dentro de la VM.
- **La liquidación de recompensas no tiene testigo en la ejecución** (la consulta de recompensa
  devuelve pendiente más acumulada, así que no cambia al cobrarse); se evidencia por el control.
- **Un solo commit y una sola plataforma.**

### Lo que la serie NO cubre (unión de los informes)

- Otras causas de fallo del marco distintas de `REVERT`: `OUT_OF_ENERGY`, `OUT_OF_TIME`, excepciones
  de la VM. Es probablemente la ampliación con más valor.
- Más de dos niveles de anidamiento.
- **Descartar de más:** que el marco externo conserve sus **propios** cambios de Stake mientras se
  descarta el interno. Solo se comprueba que no se conserve de más.
- `DELEGATECALL` y `CALLCODE` (donde el contexto y el dueño del stake cambian) y `STATICCALL`.
- Varias operaciones de Stake en la misma transacción.
- Combinaciones de estado no probadas: energía o poder de TRON con historial de pendientes,
  desbloquear con recursos delegados presentes, el límite de 32 pendientes, la ruta de delegaciones
  inválidas, recompensas con varios ciclos o con instantánea previa de votos, y las ramas del
  procesador de deshacer delegación con el destino recreado tras un `SELFDESTRUCT` o inexistente.
- El registro de transacciones internas y de eventos de la ejecución revertida (no es estado
  persistido), el procesamiento real de bloques, el cobro de ancho de banda y la red.

## 6. Errores de la prueba durante el desarrollo (no eran del código)

Se anotan todos, porque la fiabilidad de «pasa» depende de saber cuáles fueron:

- **`FreezeV2RevertTest`, 3 ejecuciones.** (1) Fallaron todas en el despliegue, antes de cualquier
  aserción sobre Stake: el mismo bytecode desplegado dos veces daba la misma dirección, y faltaba
  `--debug`. (2) El escenario anidado comparaba un contador global contra la cuenta equivocada. (3)
  Corregido, pasa.
- **`UnfreezeV2RevertTest`, 2 ejecuciones más una ampliación.** (1) Fallaron los 5 escenarios por una
  expectativa errónea: olvidé que congelar descuenta el TRX del saldo disponible. (2) Con eso corregido
  pasan. Además, antes de ejecutar descubrí que el congelado ya inicializa el poder de TRON antiguo,
  y al leer los resultados vi un hueco en mi propia cobertura (el primer desbloqueo de una cuenta), que
  cerré añadiendo un sexto escenario.
- **Hueco de la serie:** `FREEZEBALANCEV2` no tenía «revierte el interno»; cerrado (sección 4).
- El resto de las clases pasaron a la primera.

Fallos de entorno de la primera comprobación, todos previos a cualquier resultado: el build exige
Java 8 y solo había Java 21 (se instaló JDK 8); el Gradle del sistema (8.14.3) rompía el build y el
proyecto fija 7.6.4 (se usa `./gradlew`); Maven Central dio 429 y se resolvió reintentando; jitpack.io
y repo.spring.io dieron 403 y 401 y no eran necesarios.

## 7. Observaciones al margen (no forman parte de la conclusión)

Son hechos observados mientras se probaba; no se han evaluado y no se sacan conclusiones:

- Todo desbloqueo, incluso el primero de una cuenta sin votos, escribe el ciclo de inicio de
  recompensas en `DelegationStore` (observado: `0/−1 → 6/−1`).
- Tras deshacer del todo una delegación, el registro de delegación permanece con saldos a cero
  mientras que las dos entradas de índice se borran. No se ha evaluado si es intencionado.
- En el ayudante `unDelegateResource` de `FreezeV2Test`, la aserción de uso del destino resta el uso
  trasladado en ancho de banda pero lo suma en energía; no se manifiesta porque el uso es 0 en esa
  prueba. Es una observación sobre la prueba existente, no sobre el código.

## 8. Cómo reproducirlo

```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 PATH=$JAVA_HOME/bin:$PATH
cd java-tron
./gradlew --no-daemon --max-workers=1 :framework:test \
  --tests "org.tron.common.runtime.vm.FreezeV2RevertTest" \
  --tests "org.tron.common.runtime.vm.NestedFreezeV2RevertTest" \
  --tests "org.tron.common.runtime.vm.DelegateResourceRevertTest" \
  --tests "org.tron.common.runtime.vm.UnDelegateResourceRevertTest" \
  --tests "org.tron.common.runtime.vm.NestedStakeV2RevertTest" \
  --tests "org.tron.common.runtime.vm.UnfreezeV2RevertTest" \
  --tests "org.tron.common.runtime.vm.NestedUnfreezeV2RevertTest" \
  --tests "org.tron.common.runtime.vm.WithdrawAndCancelUnfreezeV2RevertTest"
```

Cada clase lleva un ensamblador con etiquetas propio (hay copias deliberadas) para no modificar las
pruebas ya verificadas al añadir las siguientes.
