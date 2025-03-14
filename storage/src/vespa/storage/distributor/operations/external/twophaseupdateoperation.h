// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include "newest_replica.h"
#include <vespa/storage/distributor/persistencemessagetracker.h>
#include <vespa/storage/distributor/operations/sequenced_operation.h>
#include <vespa/document/update/documentupdate.h>
#include <set>
#include <optional>

namespace document { class Document; }

namespace storage::api {
class UpdateCommand;
class UpdateReply;
class CreateBucketReply;
class ReturnCode;
}

namespace storage::distributor {

class DistributorBucketSpace;
class GetOperation;
class UpdateMetricSet;

/*
 * General functional outline:
 *
 * if bucket is consistent and all copies are in sync
 *   send updates directly to nodes
 * else
 *   start safe (slow) path
 *
 * Slow path:
 *
 * send Get for document to update to inconsistent copies
 * if get reply has document
 *   apply updates and send new put
 * else if create-if-non-existing set on update
 *   create new blank document
 *   apply updates and send new put
 * else
 *   reply with not found
 *
 * Note that the above case also implicitly handles the case in which a
 * bucket does not exist.
 *
*/


class TwoPhaseUpdateOperation : public SequencedOperation
{
public:
    TwoPhaseUpdateOperation(const DistributorNodeContext& node_ctx,
                            DistributorStripeOperationContext& op_ctx,
                            const DocumentSelectionParser& parser,
                            DistributorBucketSpace& bucketSpace,
                            std::shared_ptr<api::UpdateCommand> msg,
                            DistributorMetricSet& metrics,
                            SequencingHandle sequencingHandle = SequencingHandle());
    ~TwoPhaseUpdateOperation() override;

    void onStart(DistributorStripeMessageSender& sender) override;

    const char* getName() const noexcept override { return "twophaseupdate"; }

    std::string getStatus() const override { return ""; }

    void onReceive(DistributorStripeMessageSender&,
                   const std::shared_ptr<api::StorageReply>&) override;

    void onClose(DistributorStripeMessageSender& sender) override;

    void on_cancel(DistributorStripeMessageSender& sender, const CancelScope& cancel_scope) override;

    // Exposed for unit testing
    [[nodiscard]] std::shared_ptr<api::UpdateCommand> command() const noexcept { return _updateCmd; }

private:
    enum class SendState {
        NONE_SENT,
        UPDATES_SENT,
        METADATA_GETS_SENT,
        SINGLE_GET_SENT,
        FULL_GETS_SENT,
        PUTS_SENT,
    };

    enum class Mode {
        FAST_PATH,
        SLOW_PATH
    };

    void transitionTo(SendState newState);
    static const char* stateToString(SendState) noexcept;

    void sendReply(DistributorStripeMessageSender&,
                   const std::shared_ptr<api::UpdateReply>&);
    void sendReplyWithResult(DistributorStripeMessageSender&, const api::ReturnCode&);
    void ensureUpdateReplyCreated();

    [[nodiscard]] std::vector<BucketDatabase::Entry> get_bucket_database_entries() const;
    [[nodiscard]] static bool isFastPathPossible(const std::vector<BucketDatabase::Entry>& entries);
    void startFastPathUpdate(DistributorStripeMessageSender& sender, std::vector<BucketDatabase::Entry> entries);
    void startSafePathUpdate(DistributorStripeMessageSender&);
    [[nodiscard]] bool lostBucketOwnershipBetweenPhases() const;
    void sendLostOwnershipTransientErrorReply(DistributorStripeMessageSender&);
    void send_operation_cancelled_reply(DistributorStripeMessageSender& sender);
    void send_feed_blocked_error_reply(DistributorStripeMessageSender& sender);
    void schedulePutsWithUpdatedDocument(
            std::shared_ptr<document::Document>,
            api::Timestamp,
            DistributorStripeMessageSender&);
    void applyUpdateToDocument(document::Document&) const;
    [[nodiscard]] std::shared_ptr<document::Document> createBlankDocument() const;
    void setUpdatedForTimestamp(api::Timestamp);
    void handleFastPathReceive(DistributorStripeMessageSender&,
                               const std::shared_ptr<api::StorageReply>&);
    void handleSafePathReceive(DistributorStripeMessageSender&,
                               const std::shared_ptr<api::StorageReply>&);
    std::shared_ptr<GetOperation> create_initial_safe_path_get_operation();
    void handle_safe_path_received_metadata_get(DistributorStripeMessageSender&,
                                                api::GetReply&,
                                                const std::optional<NewestReplica>&,
                                                bool any_replicas_failed);
    void handle_safe_path_received_single_full_get(DistributorStripeMessageSender&, api::GetReply&);
    void handleSafePathReceivedGet(DistributorStripeMessageSender&, api::GetReply&);
    void handleSafePathReceivedPut(DistributorStripeMessageSender&, const api::PutReply&);
    [[nodiscard]] bool shouldCreateIfNonExistent() const;
    bool processAndMatchTasCondition(
            DistributorStripeMessageSender& sender,
            const document::Document& candidateDoc,
            uint64_t persisted_timestamp);
    [[nodiscard]] bool satisfiesUpdateTimestampConstraint(api::Timestamp) const;
    void addTraceFromReply(api::StorageReply& reply);
    [[nodiscard]] bool hasTasCondition() const noexcept;
    void replyWithTasFailure(DistributorStripeMessageSender& sender,
                             std::string_view message);
    bool may_restart_with_fast_path(const api::GetReply& reply);
    [[nodiscard]] bool replica_set_unchanged_after_get_operation() const;
    void restart_with_fast_path_due_to_consistent_get_timestamps(DistributorStripeMessageSender& sender);
    // Precondition: reply has not yet been sent.
    [[nodiscard]] std::string update_doc_id() const;

    using ReplicaState = std::vector<std::pair<document::BucketId, uint16_t>>;

    UpdateMetricSet&                    _updateMetric;
    PersistenceOperationMetricSet&      _putMetric;
    PersistenceOperationMetricSet&      _put_condition_probe_metrics;
    PersistenceOperationMetricSet&      _getMetric;
    PersistenceOperationMetricSet&      _metadata_get_metrics;
    std::shared_ptr<api::UpdateCommand> _updateCmd;
    std::shared_ptr<api::UpdateReply>   _updateReply;
    const DistributorNodeContext&       _node_ctx;
    DistributorStripeOperationContext&  _op_ctx;
    const DocumentSelectionParser&      _parser;
    DistributorBucketSpace&             _bucketSpace;
    SentMessageMap                      _sentMessageMap;
    SendState                           _sendState;
    Mode                                _mode;
    mbus::Trace                         _trace;
    document::BucketId                  _updateDocBucketId;
    ReplicaState                        _replicas_at_get_send_time;
    std::optional<framework::MilliSecTimer> _single_get_latency_timer;
    uint16_t                            _fast_path_repair_source_node;
    bool                                _use_initial_cheap_metadata_fetch_phase;
    bool                                _replySent;
};

}
