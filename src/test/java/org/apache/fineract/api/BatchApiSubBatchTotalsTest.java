package org.apache.fineract.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.fineract.operations.Batch;
import org.apache.fineract.operations.BatchDTO;
import org.apache.fineract.operations.BatchRepository;
import org.apache.fineract.operations.Transfer;
import org.apache.fineract.operations.TransferRepository;
import org.apache.fineract.operations.TransferStatus;
import org.apache.fineract.operations.VariableRepository;
import org.junit.jupiter.api.Test;

/**
 * PHEE-421: closedloop stores every transfer under the parent batchId, and a sub-batch with no transfers of its own is
 * counted from the parent's. The parent summary then added the sub-batches up, so a 4-transfer batch split into two
 * sub-batches was reported as Total 8. The repositories are plain proxies; this module deliberately has no Mockito.
 */
class BatchApiSubBatchTotalsTest {

    private static final String BATCH_ID = "B1";

    @Test
    void closedloopBatchSplitIntoSubBatchesReportsEachTransferOnce() throws Exception {
        List<Transfer> parentTransfers = List.of(transfer(TransferStatus.COMPLETED, "10"), transfer(TransferStatus.COMPLETED, "20"),
                transfer(TransferStatus.FAILED, "30"), transfer(TransferStatus.COMPLETED, null));
        List<Batch> rows = List.of(batch(null), batch("S1"), batch("S2"));

        BatchDTO summary = summarise(rows, Map.of(BATCH_ID, parentTransfers));

        assertEquals(4L, summary.getTotal());
        assertEquals(3L, summary.getSuccessful());
        assertEquals(1L, summary.getFailed());
        assertEquals(0, new BigDecimal("60").compareTo(summary.getTotalAmount()));
    }

    @Test
    void subBatchesWithTheirOwnTransfersAreStillAddedUp() throws Exception {
        List<Batch> rows = List.of(batch(null), batch("S1"), batch("S2"));
        Map<String, List<Transfer>> transfersByBatchId = Map.of("S1",
                List.of(transfer(TransferStatus.COMPLETED, "10"), transfer(TransferStatus.COMPLETED, "20")), "S2",
                List.of(transfer(TransferStatus.FAILED, "30")), BATCH_ID, List.of());

        BatchDTO summary = summarise(rows, transfersByBatchId);

        assertEquals(3L, summary.getTotal());
        assertEquals(2L, summary.getSuccessful());
        assertEquals(1L, summary.getFailed());
    }

    private static BatchDTO summarise(List<Batch> rows, Map<String, List<Transfer>> transfersByBatchId) throws Exception {
        BatchApi api = new BatchApi();
        set(api, "batchRepository", proxy(BatchRepository.class, (method, args) -> switch (method) {
            case "findAllByBatchId" -> rows;
            case "findParentsByBatchId" -> rows.stream().filter(b -> b.getSubBatchId() == null).toList();
            case "findAll" -> new ArrayList<>(rows);
            case "save" -> args[0];
            default -> null;
        }));
        set(api, "transferRepository", proxy(TransferRepository.class,
                (method, args) -> "findAllByBatchId".equals(method) ? transfersByBatchId.getOrDefault((String) args[0], List.of()) : null));
        set(api, "variableRepository", proxy(VariableRepository.class,
                (method, args) -> "findByWorkflowInstanceKeyAndVariableName".equals(method) ? Optional.empty() : null));

        Object body = api.batchAggregation(BATCH_ID, null, "aggregate").getBody();
        return (BatchDTO) body;
    }

    private interface Answer {

        Object answer(String method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Answer answer) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            return answer.answer(method.getName(), args);
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = BatchApi.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Batch batch(String subBatchId) {
        Batch batch = new Batch();
        batch.setBatchId(BATCH_ID);
        batch.setSubBatchId(subBatchId);
        batch.setResult_file("already-generated.csv"); // skip the result-file upload
        return batch;
    }

    private static Transfer transfer(TransferStatus status, String amount) {
        Transfer transfer = new Transfer();
        transfer.setBatchId(BATCH_ID);
        transfer.setStatus(status);
        transfer.setAmount(amount == null ? null : new BigDecimal(amount));
        return transfer;
    }
}
