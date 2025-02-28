// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.

#pragma once

#include "connector/connector.h"
#include "exec/pipeline/pipeline_builder.h"
#include "exec/pipeline/scan/balanced_chunk_buffer.h"
#include "exec/pipeline/scan/scan_operator.h"
#include "exec/workgroup/work_group_fwd.h"

namespace starrocks {

class ScanNode;

namespace pipeline {

class ConnectorScanOperatorFactory final : public ScanOperatorFactory {
public:
    using ActiveInputKey = std::pair<int32_t, int32_t>;
    using ActiveInputSet = phmap::parallel_flat_hash_set<
            ActiveInputKey, typename phmap::Hash<ActiveInputKey>, typename phmap::EqualTo<ActiveInputKey>,
            typename std::allocator<ActiveInputKey>, NUM_LOCK_SHARD_LOG, std::mutex, true>;

    ConnectorScanOperatorFactory(int32_t id, ScanNode* scan_node, size_t dop, ChunkBufferLimiterPtr buffer_limiter);

    ~ConnectorScanOperatorFactory() override = default;

    Status do_prepare(RuntimeState* state) override;
    void do_close(RuntimeState* state) override;
    OperatorPtr do_create(int32_t dop, int32_t driver_sequence) override;
    BalancedChunkBuffer& get_chunk_buffer() { return _chunk_buffer; }
    ActiveInputSet& get_active_inputs() { return _active_inputs; }

private:
    // TODO: refactor the OlapScanContext, move them into the context
    BalancedChunkBuffer _chunk_buffer;
    ActiveInputSet _active_inputs;
};

class ConnectorScanOperator final : public ScanOperator {
public:
    ConnectorScanOperator(OperatorFactory* factory, int32_t id, int32_t driver_sequence, int32_t dop,
                          ScanNode* scan_node);

    ~ConnectorScanOperator() override = default;

    Status do_prepare(RuntimeState* state) override;
    void do_close(RuntimeState* state) override;
    ChunkSourcePtr create_chunk_source(MorselPtr morsel, int32_t chunk_source_index) override;
    connector::ConnectorType connector_type();

    // TODO: refactor it into the base class
    void attach_chunk_source(int32_t source_index) override;
    void detach_chunk_source(int32_t source_index) override;
    bool has_shared_chunk_source() const override;
    ChunkPtr get_chunk_from_buffer() override;
    size_t num_buffered_chunks() const override;
    size_t buffer_size() const override;
    size_t buffer_capacity() const override;
    size_t default_buffer_capacity() const override;
    ChunkBufferTokenPtr pin_chunk(int num_chunks) override;
    bool is_buffer_full() const override;
    void set_buffer_finished() override;
};

class ConnectorChunkSource final : public ChunkSource {
public:
    ConnectorChunkSource(int32_t scan_operator_id, RuntimeProfile* runtime_profile, MorselPtr&& morsel,
                         ScanOperator* op, vectorized::ConnectorScanNode* scan_node, BalancedChunkBuffer& chunk_buffer);

    ~ConnectorChunkSource() override;

    Status prepare(RuntimeState* state) override;
    void close(RuntimeState* state) override;

private:
    Status _read_chunk(RuntimeState* state, ChunkPtr* chunk) override;

    connector::DataSourcePtr _data_source;
    vectorized::ConnectorScanNode* _scan_node;
    const int64_t _limit; // -1: no limit
    const std::vector<ExprContext*>& _runtime_in_filters;
    const vectorized::RuntimeFilterProbeCollector* _runtime_bloom_filters;

    // copied from scan node and merge predicates from runtime filter.
    std::vector<ExprContext*> _conjunct_ctxs;

    // =========================
    RuntimeState* _runtime_state = nullptr;
    bool _opened = false;
    bool _closed = false;
    uint64_t _rows_read = 0;
    uint64_t _bytes_read = 0;
};

} // namespace pipeline
} // namespace starrocks
