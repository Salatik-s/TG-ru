import Foundation

public enum FamilyReadEvent: Equatable {
    case partial(Int, tag: Int)
    case data(Data, tag: Int)
    case closed(FamilyTransportError?)
}

/// Queue-confined exact reads. EOF is held while buffered tail bytes await the next read.
/// This matters when MTProto first requests a length field, then its >64KiB packet body.
public final class FamilyReadBuffer {
    private struct Request { let length: Int; let tag: Int; var data = Data() }
    private var requests: [Request] = []
    private var chunks: [Data] = []
    private var chunkIndex = 0, offset = 0
    public private(set) var bufferedBytes = 0
    public private(set) var ended = false
    public private(set) var terminal = false
    private var endError: FamilyTransportError?
    private let limit: Int

    public init(limit: Int = 32 * 1024 * 1024) { self.limit = limit }

    public func read(length: Int, tag: Int) throws -> [FamilyReadEvent] {
        guard !terminal else { throw FamilyTransportError.closed }
        guard length > 0 && length <= FamilyVLESS.maximumMessage, requests.count < 32 else { throw FamilyTransportError.invalidRead }
        requests.append(Request(length: length, tag: tag))
        return pump()
    }

    public func append(_ data: Data) throws -> [FamilyReadEvent] {
        guard !ended && !terminal else { throw FamilyTransportError.closed }
        guard data.count <= limit - bufferedBytes else { throw FamilyTransportError.bufferLimit }
        if !data.isEmpty { chunks.append(data); bufferedBytes += data.count }
        return pump()
    }

    public func end(error: FamilyTransportError? = nil) -> [FamilyReadEvent] {
        guard !ended && !terminal else { return [] }
        ended = true; endError = error
        return pump()
    }

    public func abort(_ error: FamilyTransportError) -> [FamilyReadEvent] {
        guard !terminal else { return [] }
        terminal = true; ended = true
        chunks.removeAll(); requests.removeAll(); bufferedBytes = 0
        return [.closed(error)]
    }

    private func pump() -> [FamilyReadEvent] {
        var events: [FamilyReadEvent] = []
        while !requests.isEmpty && bufferedBytes > 0 {
            let count = min(requests[0].length - requests[0].data.count, chunks[chunkIndex].count - offset)
            let start = chunks[chunkIndex].startIndex + offset
            requests[0].data.append(chunks[chunkIndex].subdata(in: start ..< start + count))
            offset += count; bufferedBytes -= count
            events.append(.partial(count, tag: requests[0].tag))
            if offset == chunks[chunkIndex].count {
                chunks[chunkIndex] = Data() // Release consumed storage before compacting the ring.
                chunkIndex += 1; offset = 0
            }
            if requests[0].data.count == requests[0].length {
                let request = requests.removeFirst()
                events.append(.data(request.data, tag: request.tag))
            }
        }
        if chunkIndex == chunks.count { chunks.removeAll(keepingCapacity: false); chunkIndex = 0 }
        else if chunkIndex >= 64 { chunks.removeFirst(chunkIndex); chunkIndex = 0 }
        if ended && bufferedBytes == 0 && !terminal {
            terminal = true
            events.append(.closed(requests.isEmpty ? endError : (endError ?? .truncatedRead)))
            requests.removeAll()
        }
        return events
    }
}

/// One in-flight WS send. Count bytes until its completion, not when handed to URLSession.
public final class FamilyWriteBuffer {
    private var chunks: [Data] = []
    private var head = 0, offset = 0, inFlight = 0
    public private(set) var queuedBytes = 0
    private let limit: Int, chunkSize: Int
    public init(limit: Int = 16 * 1024 * 1024, chunkSize: Int = 64 * 1024) {
        precondition(limit > 0 && chunkSize > 0)
        self.limit = limit; self.chunkSize = chunkSize
    }
    public func append(_ data: Data) throws {
        guard data.count <= limit - queuedBytes else { throw FamilyTransportError.bufferLimit }
        if !data.isEmpty { chunks.append(data); queuedBytes += data.count }
    }
    public func next() -> Data? {
        guard inFlight == 0, queuedBytes > 0 else { return nil }
        let length = min(chunkSize, chunks[head].count - offset)
        inFlight = length
        let start = chunks[head].startIndex + offset
        return chunks[head].subdata(in: start ..< start + length)
    }
    public func acknowledge() {
        guard inFlight > 0 else { return }
        queuedBytes -= inFlight; offset += inFlight; inFlight = 0
        if offset == chunks[head].count { chunks[head] = Data(); head += 1; offset = 0 }
        if head == chunks.count { chunks.removeAll(); head = 0 }
        else if head >= 64 { chunks.removeFirst(head); head = 0 }
    }
    public func clear() { chunks.removeAll(); queuedBytes = 0; head = 0; offset = 0; inFlight = 0 }
}
