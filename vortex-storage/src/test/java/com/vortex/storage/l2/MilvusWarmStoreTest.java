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
