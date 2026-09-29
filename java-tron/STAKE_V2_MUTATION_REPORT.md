# Prueba de mutación de las pruebas de reversión de Stake 2.0

**Qué es esto:** una validación **de las pruebas**, no un hallazgo sobre java-tron. Se rompió el
código a propósito, de forma temporal, para comprobar que las pruebas de la serie fallan cuando
deben. Que las pruebas detecten errores introducidos deliberadamente **no demuestra que esos errores
existan en el código original**, y el código original no ha mostrado ninguna discrepancia con la
propiedad probada (los cambios de una operación de Stake 2.0 se descartan cuando revierte la
ejecución que la contiene). Son dos avances distintos y conviene no mezclarlos.

**Resultado en una línea:** los 11 mutantes reales fueron detectados, el control equivalente no
provocó ningún fallo, y con el código restaurado y reconstruido por completo las 48 pruebas pasan.

## 1. Método

- **Mutantes.** Cada uno cambia un punto de los fuentes principales del módulo `actuator` con un
  reemplazo de texto exacto, comprobado antes (el texto a cambiar aparece exactamente el número de
  veces esperado; una comprobación en seco detectó un desajuste en M0 que se corrigió antes de
  ejecutar). Simulan dos familias de fallo plausibles: una **escritura que se salta la capa de
  repositorio y va directa al almacén** (deja de descartarse al revertir) y un **`commit` indebido**
  (se confirma un marco que había revertido).
- **Predicciones antes de ejecutar.** Para cada mutante se fijó qué pruebas deberían fallar, en el
  propio script (`mutation/stake_v2_revert_mutants.py`), antes de la primera ejecución.
- **Ejecución.** Para cada mutante se aplica el cambio, se ejecutan solo las clases de pruebas
  relevantes (una invocación de Gradle, `--max-workers=1`, Java 8, Gradle 7.6.4), se leen los XML de
  JUnit y **siempre** se restaura el archivo con `git checkout` (en un `finally`), comprobando que los
  archivos rastreados quedan sin cambios. Los mutantes nunca se confirmaron ni se subieron.
- **Contabilidad.** Una prueba cuenta como «muerta» si falla; se cuenta una vez aunque el plugin de
  reintentos la repita (hasta 5 veces, y ninguna si fallan más de 20). Para cada prueba muerta se
  registra la **primera aserción fallida** y se clasifica: comparación **posterior a la reversión**
  (estado que quedó tras el `REVERT`), lectura **en curso** (antes de revertir), comprobación de
  forma, o control. Los 12 experimentos se ejecutaron (`RAN`), ninguno tuvo errores de compilación y
  ninguno tuvo respuestas 429.

Entorno: mismo que en la serie (Linux x86_64, OpenJDK 1.8.0_504, upstream `b33eed8`, pruebas en
`6621846`).

## 2. Resultados por mutante

Los conteos son «fallaron / ejecutadas» de las clases elegidas. «Detección» indica el tipo de la
primera aserción fallida.

| Id | Qué se cambió | Se esperaba que fallaran | Resultado | Detección (primera aserción fallida) |
|---|---|---|---|---|
| **M0** (control equivalente) | `Program.java`: solo el texto de un mensaje de log (2 sitios) | ninguna | 0 / 8 | — |
| **M1** | `VMActuator.java`: `rootRepository.commit()` también en la rama de `REVERT` | las 32 pruebas cuya transacción termina en `REVERT` (mismo marco y «revierte el externo») | 32 / 48; pasan las 16 de «revierte el interno» | posterior a la reversión; p. ej. `owner: frozenV2, resource 0 expected:<10000000> but was:<6000000>` |
| **M2** | `Program.callToAddress`: `deposit.commit()` también cuando el llamado revirtió sin excepción | las 16 de «revierte el interno» | 16 / 48, exactamente esas | posterior; p. ej. `freeze bandwidth / inner reverts after revert: available balance expected:<100000000000000000> but was:<99999999995000000>` |
| **M3** | `FreezeBalanceV2Processor`: peso global de ancho de banda escrito directo al almacén | 4 (las 2 de `FreezeV2RevertTest` y las 2 de ancho de banda de `NestedFreezeV2RevertTest`) | 4 / 14 | posterior; `global weight counter, resource 0 expected:<0> but was:<5>` |
| **M4** | `UnfreezeBalanceV2Processor`: peso global de energía directo | 4 (energía con historial y migración, en el mismo marco y anidadas) | 4 / 22 | posterior; `global weights … [1]: expected:<1000> but was:<600>` |
| **M5** | `UnfreezeBalanceV2Processor`: `repo.updateVotes` → escritura directa del `VotesStore` (2 sitios) | 9 (poder de TRON mitad y total, y migración; mismo marco y anidadas) | 9 / 16 | posterior; `votes record: expected array was null` y `votes record: array lengths differed …` |
| **M6** | `VoteRewardUtil`: ciclo de inicio (rama sin votos) directo a `DelegationStore` | 3 (el primer desbloqueo, 1 + 2) | 3 / 16 | posterior; `reward begin cycle expected:<0> but was:<6>` |
| **M7** | `UnDelegateResourceProcessor`: los dos borrados de índice hechos directos en el almacén | 6 (deshacer **del todo**, 2 + 4) | 6 / 16; pasan las parciales | posterior; `from-index entry: actual array was null` |
| **M8** | `WithdrawExpireUnfreezeProcessor`: la cuenta escrita **solo** directo al almacén (sin el repositorio) | 3 (retirar) | 3 / 6 | **lectura en curso**: `in-execution available balance (BALANCE) expected:<99999999975000000> but was:<99999999970000000>` |
| **M8b** | igual, pero **manteniendo** `repo.updateAccount` y añadiendo la escritura directa | 3 (retirar) | 3 / 6 | posterior; `after revert: available balance expected:<99999999970000000> but was:<99999999975000000>` |
| **M9** | `DelegateResourceProcessor`: registro de delegación directo al almacén | 6 (delegar, mismo marco 2 + anidadas 4) | 6 / 18 | posterior; `delegation record (lock=false key): expected array was null` |
| **M10** | `CancelAllUnfreezeV2Processor`: peso global de poder de TRON directo | 3 (cancelar) | 3 / 6 | posterior; `global weights … [2]: expected:<6> but was:<10>` |

Duración por experimento: entre 56 s (M0) y 270 s (M2).

**Lectura:**

- **Todas las predicciones escritas coincidieron** con las pruebas que fallaron, incluido el reparto
  fino: M2 mata solo las 16 de «revierte el interno»; M1 no mata ninguna de esas 16 (su transacción
  termina en `SUCCESS`); M7 mata las de deshacer del todo y no las parciales; M6 mata solo las del
  primer desbloqueo; M3, M4 y M8–M10 matan solo las de su operación y recurso. Ese reparto selectivo
  es lo que da valor al resultado: las pruebas no fallan por casualidad.
- **M2 valida la forma «revierte el interno»**, que se añadió porque `FreezeV2RevertTest` no la tenía.
  **M6 valida el sexto escenario de desbloqueo** (el primer desbloqueo): sin él, M6 habría
  sobrevivido a toda la clase.
- **M1 y M2** filtran muchas cosas a la vez (cuentas, saldos, votos…); cada prueba se detiene en su
  primera aserción fallida, así que su detección solo acredita esa primera comparación.

## 3. Defecto de mi predicado en M5 (se conserva en el registro)

El script informó de **2 «fallos inesperados»** en M5. Eran exactamente
`NestedUnfreezeV2RevertTest.legacyEnergyUnfreezeInnerRevert…` y `…OuterRevert…`. La causa fue un
error mío en el código del predicado: buscaba el texto `"Legacy"` con mayúscula, pero esos métodos se
llaman `legacyEnergyUnfreeze…` con minúscula, así que el script no los predijo aunque mi predicción
escrita sí los incluía (9 en total: 3 en el mismo marco y 6 anidadas). Los fallos reales fueron 9 y
coinciden con la predicción escrita. **El script tal como se ejecutó se conserva** en
`mutation/stake_v2_revert_mutants_as_executed.py`, con el defecto marcado con un comentario
`KNOWN DEFECT`; el script corregido (`stake_v2_revert_mutants.py`) ya usa un predicado que reconoce
`legacy`. El resultado de la ejecución (9 fallos, 2 «inesperados») se conserva sin retocar en
`mutation/evidence/`. Que la predicción escrita coincida no borra ese error del script.

## 3b. Salvedad del script ejecutado y cómo se respaldan las cifras

Una revisión posterior señaló dos debilidades reales del script tal como se ejecutó:

- **Podía tomar por nuevos unos XML antiguos.** No vaciaba el directorio de resultados ni comprobaba la
  fecha de los archivos, y daba una ejecución por realizada (`RAN`) si encontraba XML de alguna de las
  clases esperadas. Si Gradle hubiera fallado antes de ejecutar pruebas (por ejemplo, por un error de
  compilación), podría haber leído los XML del mutante anterior.
- **No contaba las pruebas omitidas:** una prueba omitida contaba como no fallida.

**Eso no demuestra que las cifras sean erróneas; hay que respaldarlas.** Con los registros conservados
(`mutation/evidence/`, antes solo en el directorio temporal de la sesión):

- En los **12 experimentos**, el registro de Gradle muestra que la compilación fue correcta, que la
  tarea `:framework:test` se ejecutó, y que el **conjunto de pruebas fallidas impreso en el registro es
  idéntico al del archivo de resultados** (`BUILD FAILED` en los 11 mutantes y `BUILD SUCCESSFUL` en
  M0). Además, los mensajes de fallo son propios de cada mutante (p. ej. `reward begin cycle` solo en
  M6), lo que no ocurriría con resultados heredados.
- **Ninguna prueba omitida:** las 8 clases no usan `@Ignore` ni `Assume`, y los XML de las ejecuciones
  de la serie muestran `skipped="0"`.

**Script corregido.** El script exactamente como se ejecutó queda como historia en
`mutation/stake_v2_revert_mutants_as_executed.py`. El corregido (`stake_v2_revert_mutants.py`):
vacía el directorio de resultados antes de cada ejecución; lee solo XML escritos después de empezar;
cuenta las pruebas omitidas; compara los fallos del XML con las líneas `FAILED` del registro de Gradle
(y marca `INCONSISTENT` o `NO_RESULTS` si no coinciden o falta una clase); y corrige el predicado de M5.
**Prueba de humo:** se ejecutó sobre M0 y M8b, y dio `RAN` con 0 omitidas y **los mismos fallos y el
mismo texto de la primera aserción fallida** que la ejecución original. **Los otros diez mutantes no
se volvieron a ejecutar con el script corregido**: sus cifras se apoyan en el script ejecutado más los
registros conservados.

## 4. M8 frente a M8b: qué detectaron las pruebas

M8 escribía la cuenta **solo** directamente en el almacén. Las 3 pruebas fallaron, pero la primera
aserción fallida fue la lectura **en curso** del saldo, antes de revertir: el mutante hacía que la
ejecución viera un estado incorrecto, y la comparación posterior a la reversión ni llegó a ejecutarse
(va después). Es decir, M8 **no** demostraba que esas comparaciones detecten una fuga en el almacén
de cuentas. M8b mantiene la escritura por el repositorio y añade la directa, de modo que la lectura en
curso no cambia y solo una comparación posterior puede verlo. Resultado: las 3 pruebas fallaron en
`after revert: available balance`, con el saldo 5 TRX por encima del previo, es decir, la fuga (los
5 TRX cobrados que quedaron tras el `REVERT`) fue vista por la comparación de estado. **Las dos
detecciones son válidas pero distintas**, y por eso M8 se reporta como detección por lectura en
curso y M8b como detección por estado dejado.

## 5. Qué comparadores quedan evidenciados por mutación, y cuáles no

Solo cuenta lo que fue la **primera aserción fallida** de alguna prueba muerta.

- **Evidenciados por mutación (comparación posterior a la reversión):** saldo disponible (M1, M2,
  M8b), saldos congelados V2 (M1, M2), `allowance` (M1, M2), poder de TRON antiguo (M1, M2), pesos
  globales (M3, M4, M10), registro del `VotesStore` (M5), ciclo de inicio de recompensas (M6), entrada
  de índice «desde» (M7) y registro de delegación con la clave sin bloqueo (M9). Además, las
  lecturas en curso detectaron M8.
- **No evidenciados por mutación** (comparan, y cambian en los controles, pero ningún mutante los
  hizo fallar primero): la lista de pendientes, los votos de la cuenta, la cuenta serializada
  completa, la instantánea de votos del ciclo, el ciclo de fin, la entrada de índice «hasta», el
  registro con la clave de bloqueo, las cuatro vistas de la API, la cuenta del contrato llamador y las
  comprobaciones de la comisión de energía. Su sensibilidad sigue apoyándose solo en los controles
  (cambian cuando no hay reversión), como decían los informes anteriores; aquí no se ha probado con
  un mutante propio.

## 6. Verificación de la restauración

Un árbol sin cambios no basta para asegurar que las ejecuciones posteriores usan el código original,
así que se comprobó de tres maneras:

- **Después de cada mutante**, el script comprobó que los archivos rastreados quedaban sin cambios
  (`git status` sin modificaciones) y lo registró; los 12 experimentos dieron «limpio».
- **Ejecución final de las 48 pruebas con reconstrucción completa** (`--rerun-tasks`): 48 de 48 pasan
  (0 fallos, 0 errores), en un intento, con 8 tareas `compileJava` ejecutadas y ninguna `UP-TO-DATE`.
  Los recuentos por clase coinciden con la ejecución previa a los mutantes: `FreezeV2RevertTest` 2,
  `NestedFreezeV2RevertTest` 6, `DelegateResourceRevertTest` 2, `UnDelegateResourceRevertTest` 4,
  `NestedStakeV2RevertTest` 12, `UnfreezeV2RevertTest` 6, `NestedUnfreezeV2RevertTest` 10,
  `WithdrawAndCancelUnfreezeV2RevertTest` 6.
- **Bytecode:** el `Program.class` recompilado no contiene el texto que M0 introducía (0
  apariciones), es decir, se recompiló desde el código restaurado.

Los fuentes rastreados no tienen ningún marcador de mutante y `git status` está vacío. Además, en el
commit `152ea8a` los **1.653 archivos originales de TRON** eran idénticos byte a byte a la versión de
partida (comparados con un clon de `b33eed8`: 0 ausentes, 0 distintos); lo añadido eran 19 archivos
(9 documentos, 8 pruebas, el script y un `.gitignore`).

## 7. Limitaciones

- **Son 11 mutantes elegidos a mano por mí, y conociendo las pruebas.** Un 100 % de detección es lo
  esperable y no es una estimación imparcial de la fuerza de las pruebas; sí demuestra que detectan
  esos fallos concretos.
- **Solo dos familias de fallo:** escrituras directas que se saltan la capa de repositorio y `commit`
  indebidos a nivel de llamada y de transacción. No se mutó la lógica interna de la capa de
  repositorio (`RepositoryImpl`: cómo se vuelcan las cachés por almacén, la marca de borrado de los
  índices…), ni errores de otro tipo (por ejemplo, importes o condiciones equivocados).
- **No es mutación automática ni exhaustiva**, y cada prueba se detiene en su primera aserción
  fallida (sección 5).
- Las pruebas siguen cubriendo solo lo descrito en el resumen de la serie (`REVERT`, dos niveles, un
  commit, una plataforma, sin procesamiento de bloques).

## 8. Errores míos durante el experimento (no afectan a los resultados)

- El predicado de M5 (sección 3).
- Las dos debilidades del script ejecutado (sección 3b), señaladas en una revisión posterior.
- La comprobación en seco encontró que el texto de M0 aparece dos veces; se corrigió el recuento
  antes de ejecutar.
- Mi primera clasificación de detecciones no reconocía los mensajes de la prueba más antigua
  (`FreezeV2RevertTest`), que no llevan «after revert»; los marqué «OTHER» y los reclasifiqué
  leyendo los mensajes (son comparaciones posteriores a la reversión).
- Tres deslices operativos míos con comandos de comprobación (un `grep` sin archivos que esperaba la
  entrada estándar, un `pkill` que coincidió con su propio shell y un `pgrep -f` que se autocoincidió y
  dio un falso «sigue vivo»). No tocaron el código ni las pruebas; se repitieron bien.

## 9. Conclusión

Las pruebas de la serie **detectan** las once roturas introducidas, distinguen qué escenarios deben
fallar y no fallan con un cambio equivalente; con el código restaurado y reconstruido, las 48 pasan.
Eso hace más fiable la conclusión anterior, que sigue siendo la misma y sigue acotada: **en ese
escenario, con esas pruebas, no se ha visto ninguna diferencia entre lo que exige la propiedad y lo
que hace el código original.** Esto valida las pruebas; no es un fallo descubierto en TRON y no
aporta, por sí solo, ningún elemento para un informe de seguridad.

## 10. Cómo reproducirlo

Desde la raíz del repositorio (edita fuentes de `actuator` de forma temporal y los restaura; no
hacer `commit` mientras corre; se niega a empezar si ya hay archivos rastreados modificados):

```bash
export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
python3 java-tron/mutation/stake_v2_revert_mutants.py            # los 12 experimentos (script corregido)
python3 java-tron/mutation/stake_v2_revert_mutants.py M2 M8b     # solo algunos
# resultados y registros en java-tron/mutation/out/ (o en $MUTANT_OUT), ignorado por git
```

Los registros y resultados de la ejecución original están en `java-tron/mutation/evidence/`, y el
script exactamente como se ejecutó, en `mutation/stake_v2_revert_mutants_as_executed.py`.
