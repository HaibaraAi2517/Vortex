package com.vortex.storage.l2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.dml.SearchParam;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MilvusWarmStoreTest {
    private final MilvusServiceClient client = mock(MilvusServiceClient.class);
    private final MilvusWarmStore store = new MilvusWarmStore(client, 2, "test", false, "", null, null);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void completeNamespaceListingVisitsEveryIteratorPageAndClosesIt() throws Exception {
        var iterator = mock(io.milvus.orm.iterator.QueryIterator.class);
        var first = new io.milvus.response.QueryResultsWrapper.RowRecord();
        first.put("id", "first"); first.put("namespace", "ns"); first.put("content", "first page");
        first.put("importance", 0.5); first.put("token_count", 2);
        var last = new io.milvus.response.QueryResultsWrapper.RowRecord();
        last.put("id", "last"); last.put("namespace", "ns"); last.put("content", "咖啡");
        last.put("importance", 0.5); last.put("token_count", 2);
        when(client.queryIterator(any(io.milvus.param.dml.QueryIteratorParam.class))).thenReturn(R.success(iterator));
        when(iterator.next()).thenReturn(java.util.List.of(first), java.util.List.of(last), java.util.List.of());
        assertThat(store.listByNamespace("ns", Integer.MAX_VALUE)).extracting(fragment -> fragment.getId()).containsExactly("first", "last");
        verify(iterator, times(3)).next();
        verify(iterator).close();
        verify(client, never()).query(any(QueryParam.class));
    }

    @Test
    void incompleteNamespaceScanFailsInsteadOfReturningSilentlyTruncatedResults() {
        var iterator = mock(io.milvus.orm.iterator.QueryIterator.class);
        when(client.queryIterator(any(io.milvus.param.dml.QueryIteratorParam.class))).thenReturn(R.success(iterator));
        when(iterator.next()).thenThrow(new IllegalStateException("connection lost"));
        assertThatThrownBy(() -> store.listByNamespace("ns", Integer.MAX_VALUE)).isInstanceOf(IllegalStateException.class);
        verify(iterator).close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ordinary", "quickstart-x\" or namespace != \"", "x\\\" or id != \"",
            "line\nbreak", "quote'and\\backslash"})
    void queryValuesRemainSingleStringLiterals(String value) throws Exception {
        when(client.search(any(SearchParam.class))).thenReturn(R.failed(new RuntimeException("empty fixture")));
        when(client.query(any(QueryParam.class))).thenReturn(R.failed(new RuntimeException("empty fixture")));
        when(client.delete(any(DeleteParam.class))).thenReturn(R.success(MutationResult.getDefaultInstance()));

        store.search(new float[]{1, 0}, value, 1);
        store.listByNamespace(value, 1);
        store.get(value);
        store.delete(value);

        var search = ArgumentCaptor.forClass(SearchParam.class);
        verify(client).search(search.capture());
        assertThat(mapper.readValue(search.getValue().getExpr().substring("namespace == ".length()), String.class))
                .isEqualTo(value);
        var queries = ArgumentCaptor.forClass(QueryParam.class);
        verify(client, times(2)).query(queries.capture());
        assertThat(queries.getAllValues().getFirst().getExpr())
                .isEqualTo("namespace == " + mapper.writeValueAsString(value));
        assertThat(queries.getAllValues().getLast().getExpr())
                .isEqualTo("id in [" + mapper.writeValueAsString(value) + "]");
        var deletion = ArgumentCaptor.forClass(DeleteParam.class);
        verify(client).delete(deletion.capture());
        assertThat(deletion.getValue().getExpr())
                .isEqualTo("id in [" + mapper.writeValueAsString(value) + "]");
    }

    @Test
    void failedDeleteIsNotAcknowledged() {
        when(client.delete(any(DeleteParam.class))).thenReturn(R.failed(new RuntimeException("unavailable")));
        assertThatThrownBy(() -> store.delete("id")).isInstanceOf(IllegalStateException.class);
    }
}
