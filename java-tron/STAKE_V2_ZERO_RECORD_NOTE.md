# Nota: registro de delegación a cero tras deshacer del todo

**Es una revisión breve de código y documentación, no una prueba nueva ni un hallazgo de
seguridad.** Nace de una observación al margen del resumen de la serie: tras deshacer del todo una
delegación con `UNDELEGATERESOURCE` desde un contrato, el registro de delegación permanece con saldos a
cero mientras las dos entradas de índice se borran.

**Pregunta:** ¿conservar un registro con saldo cero y eliminar sus índices produce un comportamiento
contrario a las reglas, o es simplemente la representación prevista?

**Conclusión:** no aparece ninguna contradicción concreta. Es una **diferencia de representación entre
la ruta nativa y la de contrato**, fijada por pruebas del propio proyecto y sin efecto observable en la
API pública ni en el resultado de las transacciones que he podido comprobar por lectura. Se cierra la
observación. Ver la sección 6 para lo que no se ha comprobado.

## 1. Qué hace cada ruta (por lectura del código, en `b33eed8`)

| | Transacción nativa (`UnDelegateResourceActuator`) | Opcode del contrato (`UnDelegateResourceProcessor`) |
|---|---|---|
| Registro con saldos a cero (sin bloqueo) | **lo borra** (`delegatedResourceStore.delete(unlockKey)`) | **lo reescribe a cero**; su volcado (`RepositoryImpl`) escribe sin borrar nunca |
| Entradas de índice | las borra si tampoco queda registro **con bloqueo** | las borra si el registro **sin bloqueo** queda a cero; no consulta el registro con bloqueo |

La ruta de contrato solo trabaja con el registro sin bloqueo porque el opcode de contrato nunca crea
delegaciones con bloqueo; la nativa gestiona además el desbloqueo de las caducadas.

## 2. Qué dicen las pruebas del proyecto

Ambas conductas están fijadas por pruebas existentes de upstream (comprobado leyendo su código):

- **Nativa:** `UnDelegateResourceActuatorTest#testUnDelegateForBandwidth` deshace **todo** lo delegado
  (el saldo delegado del dueño pasa a 0) y, después de ejecutar, afirma `assertNull` del registro sin
  bloqueo y comprueba los índices. Hay otras comprobaciones análogas tras ejecutar (9 de los 11
  `assertNull` sobre registros de ese archivo son posteriores a ejecutar y 2 son comprobaciones del
  estado previo); **no he verificado uno a uno** que todas correspondan a deshacer del todo.
- **Contrato:** `FreezeV2Test#testDelegateResourceOperations` delega ancho de banda y energía y las
  deshace ambas, y su ayudante `unDelegateResource` (línea 919) afirma `assertNotNull` del registro
  tras deshacer, con los saldos comprobados a cero.

No es un efecto accidental descubierto por las pruebas de la serie: el proyecto lo prueba de forma
explícita en cada ruta.

## 3. Quién lee ese registro (código principal)

- **API pública** `Wallet.getDelegatedResourceV2`: usa `nonEmptyResource(...)`, que **descarta** los
  registros con todos los saldos y caducidades a cero. Un registro a cero dejado por la ruta de contrato
  no se devuelve por esa consulta. (Que exista ese filtro indica que el diseño contempla registros
  vacíos.)
- **Delegación nativa posterior** (`DelegateResourceActuator.delegateResource`): si hay registro, suma
  sobre él; si no, crea uno. Con un registro a cero el resultado es el mismo que con uno nuevo.
- **Deshacer nativo posterior** (`UnDelegateResourceActuator.validate`): con un registro a cero y sin
  registro con bloqueo, una petición con saldo positivo falla con «insufficient delegatedFrozenBalance…»
  en lugar de «delegated Resource does not exist». Ambas son `ContractValidateException`: la
  transacción es inválida en los dos casos y solo cambia el texto del mensaje.
- **Consultas del opcode** (`queryResourceV2`): suman los saldos de ambos registros; un registro a cero
  suma 0.

## 4. Qué dice la especificación

TIP-467 (Stake 2.0, estado Final) se descargó y se buscó literalmente: no aparecen las palabras
«delete», «remove» ni «zero», y lo que dice de `undelegateresource`, `getdelegatedresourcev2` y el
opcode `0xdf` no indica qué ocurre con el registro ni con los índices cuando la delegación llega a
cero. **La especificación es silenciosa sobre este caso**, así que no permite afirmar ni negar que
conservar el registro incumpla algo.

## 5. Efectos residuales

- Un registro pequeño por cada par que se deshace del todo por la ruta de contrato (crecimiento de
  estado mínimo).
- El texto del mensaje de validación descrito arriba.
- Ninguno de los consumidores leídos distingue «ausente» de «a cero» de forma que cambie un resultado.

## 6. Lo que no se ha comprobado

- No se ha ejecutado la API ni las dos rutas una detrás de otra; las afirmaciones de la sección 3 son
  **por lectura del código**.
- Una diferencia adicional por lectura: la ruta de contrato **no considera el registro con bloqueo** al
  decidir borrar los índices. Solo podría importar si un contrato fuera dueño de una delegación con
  bloqueo; por lo que he leído eso no se puede crear (el opcode de contrato nunca bloquea y una
  transacción nativa necesita firma de la cuenta dueña). Es una inferencia de lectura, no está probada.
- No se han revisado todos los consumidores del proyecto ni otras implementaciones o herramientas
  externas que pudieran leer el almacén.
- La lectura de la especificación es de TIP-467; no se han buscado otros documentos.

## 7. Decisión

Se cierra como **representación conocida sin contradicción concreta**: no se construyen más pruebas
sobre esto. Se reabriría solo si aparece un consumidor que distinga «ausente» de «a cero» y cambie un
resultado, o si una especificación posterior exige el borrado. Esto no aporta ningún elemento para un
informe de seguridad.
