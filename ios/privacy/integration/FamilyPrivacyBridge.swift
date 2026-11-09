import Foundation
import Postbox
import SwiftSignalKit
internal import TelegramApi
import MtProtoKit
import FamilyPrivacy

// This bridge is compiled inside TelegramCore, not exposed to the UI. It never
// constructs a Telegram response or synthesizes pts/ptsCount.
func familyPrivacySuppressRequest(_ description: FunctionDescription, readEligible: Bool) -> Bool {
    var online: Bool?
    if description.name == "account.updateStatus",
       let value = description.parameters.first(where: { $0.0 == "offline" })?.1.value as? Api.Bool {
        switch value {
        case .boolFalse: online = true
        case .boolTrue: online = false
        }
    }
    return FamilyPrivacySettings.shared.shouldSuppress(method: description.name, online: online, readEligible: readEligible)
}

func familyPrivacyCanHideHistory(transaction: Transaction, peerId: PeerId) -> Bool {
    // A missing/unknown timeout is not evidence that a conversation has no TTL.
    guard peerId.namespace == Namespaces.Peer.CloudUser || peerId.namespace == Namespaces.Peer.CloudGroup || peerId.namespace == Namespaces.Peer.CloudChannel else { return false }
    let cached = transaction.getPeerCachedData(peerId: peerId)
    let timeout: CachedPeerAutoremoveTimeout
    if let value = cached as? CachedUserData { timeout = value.autoremoveTimeout }
    else if let value = cached as? CachedGroupData { timeout = value.autoremoveTimeout }
    else if let value = cached as? CachedChannelData { timeout = value.autoremoveTimeout }
    else { return false }
    switch timeout {
    case .unknown: return false
    case let .known(value): return value == nil || value!.effectiveValue == 0
    }
}

private func familyPrivacyOrdinaryMessage(transaction: Transaction, id: MessageId) -> Message? {
    guard id.namespace == Namespaces.Message.Cloud,
          familyPrivacyCanHideHistory(transaction: transaction, peerId: id.peerId),
          let message = transaction.getMessage(id) else { return nil }
    for attribute in message.attributes {
        if attribute is AutoremoveTimeoutMessageAttribute || attribute is AutoclearTimeoutMessageAttribute { return nil }
    }
    return message
}

func familyPrivacyCanHideContents(transaction: Transaction, ids: [MessageId]) -> Bool {
    guard !ids.isEmpty, ids.count <= 512 else { return false }
    return ids.allSatisfy { familyPrivacyOrdinaryMessage(transaction: transaction, id: $0) != nil }
}

func familyPrivacyReadRequest<T>(postbox: Postbox, peerId: PeerId, network: Network, data: (FunctionDescription, Buffer, DeserializeFunctionResponse<T>)) -> Signal<T, MTRpcError> {
    // Preserve the normal path while disabled, including its queue ordering.
    guard FamilyPrivacySettings.shared.isEnabled(.hideReadReceipts) else { return network.request(data) }
    return postbox.transaction { transaction -> Bool in
        return familyPrivacyCanHideHistory(transaction: transaction, peerId: peerId)
    }
    |> castError(MTRpcError.self)
    |> mapToSignal { eligible -> Signal<T, MTRpcError> in
        return network.request(data, familyPrivacyReadEligible: eligible)
    }
}

func familyPrivacyContentsRequest<T>(postbox: Postbox, ids: [MessageId], network: Network, data: (FunctionDescription, Buffer, DeserializeFunctionResponse<T>)) -> Signal<T, MTRpcError> {
    guard FamilyPrivacySettings.shared.isEnabled(.hideReadReceipts) else { return network.request(data) }
    return postbox.transaction { transaction -> Bool in
        return familyPrivacyCanHideContents(transaction: transaction, ids: ids)
    }
    |> castError(MTRpcError.self)
    |> mapToSignal { eligible -> Signal<T, MTRpcError> in
        return network.request(data, familyPrivacyReadEligible: eligible)
    }
}

func familyArchiveReceivedDeletion(transaction: Transaction, accountPeerId: PeerId, ids: [MessageId]) {
    guard FamilyPrivacySettings.shared.isEnabled(.archiveDeletedMessages) else { return }
    let accountID = String(accountPeerId.toInt64())
    let deletedAt = Date().timeIntervalSince1970
    var copies: [FamilyArchivedMessage] = []
    for id in ids.prefix(256) {
        guard let message = familyPrivacyOrdinaryMessage(transaction: transaction, id: id),
              !message.flags.intersection(.IsIncomingMask).isEmpty,
              !message.text.isEmpty, !message.isCopyProtected() else { continue }
        if let peer = transaction.getPeer(id.peerId), peer.isCopyProtectionEnabled { continue }
        if let cached = transaction.getPeerCachedData(peerId: id.peerId) as? CachedUserData,
           cached.flags.contains(.copyProtectionEnabled) || cached.flags.contains(.myCopyProtectionEnabled) { continue }
        copies.append(FamilyArchivedMessage(accountID: accountID, peerID: String(id.peerId.toInt64()), messageID: id.id, timestamp: message.timestamp, deletedAt: deletedAt, text: message.text))
    }
    guard !copies.isEmpty else { return }
    // No ReplayContext, Transaction, Message or media object escapes this scope.
    transaction.afterCommit { [copies] in FamilyDeletedArchive.shared.record(copies) }
}
