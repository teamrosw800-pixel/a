# Revisión de la ruta de bloques P2P (java-tron)

**Qué es esto.** Registro de una lectura de código de solo lectura sobre `tronprotocol/java-tron`
en el commit `b33eed8`, hecha el 2026-09-30. **No contiene ningún hallazgo.** Sirve para no repetir
trabajo ya hecho y para dejar constancia de qué controles se encontraron en su sitio.

Complementa `JAVA_TRON_MAP.md` de la rama `claude/pensive-mayer-ni6me2` (§6.3 puso el área P2P como
prioridad alta; esta revisión cubre parte de ella).

## Alcance de esta revisión

| Leído | Archivo |
|---|---|
| Sí | `net/messagehandler/BlockMsgHandler.java` |
| Sí | `net/TronNetDelegate.java` (`validBlock`, `validSignature`, `processBlock`) |
| Sí | `net/service/sync/SyncService.java` |
| Sí | `capsule/BlockCapsule.java` (`sanitize`, `validateMerkleRoot`, `calcMerkleRoot`) |
| Sí | `capsule/TransactionCapsule.java` (`getMerkleHash`) |
| Parcial | `db/Manager.java` (`pushBlock`, `switchFork`, `getVerifyTxs`) |
| No | `Manager.processBlock` en adelante, `KhaosDatabase`, `TransactionsMsgHandler`, capa libp2p |

## Controles encontrados en su sitio

1. **Integridad del cuerpo del bloque.** La raíz Merkle se calcula sobre la serialización completa de
   cada transacción (`TransactionCapsule.getMerkleHash` → `transaction.toByteArray()`), firmas
   incluidas. Un cuerpo alterado no cuadra con la cabecera.

2. **Campos protobuf desconocidos.** `BlockCapsule.sanitize()` los limpia en el bloque y en la
   cabecera. Se invoca en la ruta de difusión antes de procesar.

3. **Bloques no solicitados.** `BlockMsgHandler.check()` los rechaza con `BAD_MESSAGE` salvo en modos
   que el propio operador configura (`fastForward`, par de relevo).

4. **Tamaño de bloque.** Tope `BLOCK_SIZE + 1000` en el manejador.

5. **Orden de validación antes de difundir.** `validBlock` exige raíz Merkle correcta, firma válida y
   que el firmante esté en la lista de testigos activos, antes de reenviar.

6. **Memoria de la cola de sincronización.** `blockJustReceived` y `blockWaitToProcess` están acotadas
   por `maxPendingBlockSize` en `startFetchSyncBlock`, y solo entran bloques previamente pedidos.
   Coincide con el endurecimiento de los PR #6712 y #6717.

7. **Estado divergente al cambiar de rama.** En `switchFork`, antes de reaplicar la nueva rama, se
   limpia el resultado de verificación cacheado de cada transacción (`tx.setVerified(false)`), con un
   comentario que explica por qué. Cierra el patrón que arreglaron en #6716 / #6796 / #6864.

## Observaciones sin impacto demostrado

- **Desconexión colateral** (`SyncService.processSyncBlock`, bucle final): cuando un bloque de
  sincronización falla por una causa no clasificada como ataque, se desconecta a todos los pares que
  tienen ese bloque a la cabeza de su cola, no solo al emisor. Requeriría un bloque que pase firma y
  raíz Merkle y falle después, es decir, firmado por un SR. Impacto: desconexión de pares honestos.
  No se considera hallazgo.

- **`merkleValidated`** sigue sin reiniciarse si el bloque se modifica. Por el orden
  sanitizar → validar de las rutas leídas, no se encontró forma de aprovecharlo.

## Estado

Sin hallazgos en lo revisado. Las áreas listadas como "No" en la tabla de alcance quedan pendientes.

## Límites

Lectura dirigida, no auditoría. Que no se haya encontrado nada no significa que no haya nada: los
propios mantenedores siguen publicando arreglos en esta área (ver el issue #6969, de septiembre de
2026). Todo el trabajo fue sobre el código público, sin tocar ninguna red real.
