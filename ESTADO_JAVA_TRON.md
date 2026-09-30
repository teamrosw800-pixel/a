# Estado del trabajo sobre java-tron (Stake 2.0 y reversión)

Comprobado contra GitHub el 2026-09-30.

## Respuesta corta

**Sí, todo está subido.** Nada quedó sin guardar.

- Repositorio: https://github.com/teamrosw800-pixel/a
- Rama: `claude/pensive-mayer-ni6me2`
- Último commit: `e74e62f` — *docs(java-tron): add determinism sweep and external calibration to the map* (2026-09-30 02:36 UTC)
- 26 commits en total sobre `main`, desde la instantánea del código (`064ca23`) hasta el mapa final.
- El `pushed_at` del repositorio coincide con ese último commit: no hay nada posterior sin subir.

Este contenedor es nuevo y solo contiene `index.html`; la copia de trabajo de java-tron de las
sesiones anteriores ya no existe aquí, pero **no hace falta**: todo su contenido está en la rama de
arriba.

## Qué hay en la rama

### Código base
- `java-tron/` — instantánea de `tronprotocol/java-tron` en el commit `b33eed89a6a424c498d4fb1b03ca2c86eddf4840`
  (2026-09-11, `BLOCK_VERSION` 37), sin historial de git. Licencia LGPL-3.0 (`java-tron/LICENSE`).

### Pruebas añadidas (8 clases, 48 pruebas) — en `java-tron/framework/src/test/java/org/tron/common/runtime/vm/`
| Clase | Pruebas |
|---|---|
| `FreezeV2RevertTest` | 2 |
| `NestedFreezeV2RevertTest` | 6 |
| `DelegateResourceRevertTest` | 2 |
| `UnDelegateResourceRevertTest` | 4 |
| `NestedStakeV2RevertTest` | 12 |
| `UnfreezeV2RevertTest` | 6 |
| `NestedUnfreezeV2RevertTest` | 10 |
| `WithdrawAndCancelUnfreezeV2RevertTest` | 6 |
| **Total** | **48** |

### Informes (en `java-tron/`)
- `STAKE_V2_REVERT_SERIES_SUMMARY.md` — el resumen maestro de toda la serie.
- `STAKE_V2_REVERT_REPORT.md` — `FREEZEBALANCEV2`.
- `STAKE_V2_DELEGATE_REVERT_REPORT.md` — `DELEGATERESOURCE`.
- `STAKE_V2_UNDELEGATE_REVERT_REPORT.md` — `UNDELEGATERESOURCE`.
- `STAKE_V2_UNFREEZE_REVERT_REPORT.md` — `UNFREEZEBALANCEV2`.
- `STAKE_V2_WITHDRAW_CANCEL_REVERT_REPORT.md` — `WITHDRAWEXPIREUNFREEZE` y `CANCELALLUNFREEZEV2`.
- `STAKE_V2_NESTED_REVERT_REPORT.md` — marcos anidados (delegar / deshacer delegación).
- `STAKE_V2_NESTED_UNFREEZE_REVERT_REPORT.md` — marcos anidados (desbloquear).
- `STAKE_V2_MUTATION_REPORT.md` — prueba de mutación que valida las pruebas.
- `STAKE_V2_ZERO_RECORD_NOTE.md` — revisión de la observación del registro de delegación a cero.
- `JAVA_TRON_MAP.md` — mapa de arquitectura, puntos de entrada e historial de cambios.
- `mutation/` — el script ejecutado, el script corregido y las pruebas guardadas
  (`mutation/evidence/`, unos 540 KB de registros de Gradle y resultados).

## Resultado

**Las 48 pruebas pasan (0 fallos)**, ejecutadas juntas en una sola invocación de Gradle, en un solo
intento, sobre el mismo commit y sin errores de entorno.

Propiedad comprobada (solo esta): los cambios de una operación válida de Stake 2.0 que tiene éxito se
descartan cuando revierte la ejecución que la contiene. Seis operaciones × tres formas de reversión
(mismo marco, revierte el externo, revierte el interno). Solo se conserva la comisión de energía.

**No se encontró ninguna discrepancia del código. No se afirma ninguna vulnerabilidad ni gravedad.**
No es una evaluación de seguridad de java-tron ni prueba la ausencia de fallos.

Las pruebas ya existentes también pasaron como línea base: `FreezeV2Test` 8/8,
`DelegateResourceActuatorTest` 24/24, `UnDelegateResourceActuatorTest` 16/16,
`UnfreezeBalanceV2ActuatorTest` 19/19, `StakeV2AfterSelfDestructTest` 3/3.

### Validación por mutación
Se rompió el código a propósito, temporalmente, para comprobar que las pruebas fallan cuando deben:
11 mutantes reales y 1 control equivalente, con las predicciones fijadas antes de ejecutar. Los 11
fueron detectados por las pruebas esperadas; el control no provocó ningún fallo. Con el código
restaurado y reconstruido por completo (`--rerun-tasks`), las 48 pruebas vuelven a pasar. Ningún
mutante se llegó a subir.

## Entorno

| Dato | Valor |
|---|---|
| Plataforma | Linux x86_64, OpenJDK 1.8.0_504 |
| Build | `./gradlew` (Gradle 7.6.4, fijado por el proyecto), `--no-daemon --max-workers=1` |
| Configuración de las pruebas | `config-test.conf` + `--debug` (desactiva el límite de CPU de la VM) |

Problemas de entorno que hubo al principio, todos anteriores a cualquier resultado y ya resueltos:
el build exige Java 8 y solo había Java 21 (se instaló JDK 8); el Gradle del sistema (8.14.3) rompía
el build (se usa el `./gradlew` del proyecto); Maven Central dio 429 y se resolvió reintentando;
jitpack.io y repo.spring.io dieron 403 y 401 y no eran necesarios.

## Limitaciones

- Un solo commit y una sola plataforma.
- Sensibilidad «en algún escenario»: cada comparador cambia en el control de al menos un escenario de
  su operación, pero no en todos, y algunos nunca cambian en ninguno (la cuenta del contrato llamador,
  la clave de bloqueo del registro de delegación, las listas de índice). Se comparan igualmente, pero
  su capacidad de detección no está probada del todo.
- La mutación es limitada: 11 mutantes elegidos a mano de dos familias, no exhaustiva, y no se mutó la
  lógica interna de `RepositoryImpl`.
- Los votos, ciclos e índices de recompensa se sembraron escribiendo directamente en los almacenes
  (como hace `FreezeV2Test`), no con `VOTEWITNESS` dentro de la VM.
- La liquidación de recompensas no tiene testigo en la ejecución; se evidencia por el control.

### Lo que NO se cubre
- Otras causas de fallo del marco distintas de `REVERT`: `OUT_OF_ENERGY`, `OUT_OF_TIME`, excepciones de
  la VM. Es probablemente la ampliación con más valor.
- Más de dos niveles de anidamiento.
- Descartar de más: que el marco externo conserve sus **propios** cambios mientras se descarta el interno.
- `DELEGATECALL`, `CALLCODE` y `STATICCALL`.
- Varias operaciones de Stake en la misma transacción.
- Combinaciones de estado no probadas: energía o poder de TRON con historial de pendientes, desbloquear
  con recursos delegados presentes, el límite de 32 pendientes, la ruta de delegaciones inválidas,
  recompensas con varios ciclos, y las ramas de deshacer delegación con el destino recreado tras un
  `SELFDESTRUCT` o inexistente.
- El registro de transacciones internas y de eventos, el procesamiento real de bloques, el cobro de
  ancho de banda y la red.

## Errores de las pruebas durante el desarrollo (no eran del código)

- `FreezeV2RevertTest`, 3 ejecuciones: (1) fallaron en el despliegue, antes de cualquier aserción sobre
  Stake — el mismo bytecode desplegado dos veces daba la misma dirección, y faltaba `--debug`;
  (2) el escenario anidado comparaba un contador global contra la cuenta equivocada; (3) corregido, pasa.
- `UnfreezeV2RevertTest`, 2 ejecuciones más una ampliación: (1) fallaron los 5 escenarios por una
  expectativa errónea (congelar descuenta el TRX del saldo disponible); (2) corregido, pasan. Además se
  añadió un sexto escenario para el primer desbloqueo de una cuenta, que faltaba.
- Hueco de la serie: `FREEZEBALANCEV2` no tenía la forma «revierte el interno»; cerrado con
  `NestedFreezeV2RevertTest`, que pasó a la primera.
- Las demás clases pasaron a la primera.

## Observaciones al margen (no forman parte de la conclusión)

- Todo desbloqueo, incluso el primero de una cuenta sin votos, escribe el ciclo de inicio de recompensas
  en `DelegationStore` (observado: `0/−1 → 6/−1`).
- Tras deshacer del todo una delegación, el registro de delegación permanece con saldos a cero mientras
  que las dos entradas de índice se borran. Revisado en `STAKE_V2_ZERO_RECORD_NOTE.md`: la ruta nativa lo
  borra y la de contrato no, ambas fijadas por pruebas del proyecto, la API lo filtra y la
  especificación no lo trata; sin contradicción concreta.
- En el ayudante `unDelegateResource` de `FreezeV2Test`, la aserción de uso del destino resta el uso
  trasladado en ancho de banda pero lo suma en energía; no se manifiesta porque el uso es 0 ahí. Es una
  observación sobre la prueba existente, no sobre el código.

## Cómo reproducirlo

```bash
git clone https://github.com/teamrosw800-pixel/a
cd a && git checkout claude/pensive-mayer-ni6me2
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
