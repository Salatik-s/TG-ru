import XCTest
@testable import FamilyTransportCore

final class FamilyTransportTests: XCTestCase {
    let uuid = UUID(uuidString: "00112233-4455-6677-8899-aabbccddeeff")!

    func testVLESSWireHeadersKeepEachDCDestination() throws {
        let v4 = try FamilyVLESS.request(uuid: uuid, host: "149.154.167.51", port: 443)
        XCTAssertEqual(Array(v4), [0,0,17,34,51,68,85,102,119,136,153,170,187,204,221,238,255,0,1,1,187,1,149,154,167,51])
        let v6 = try FamilyVLESS.request(uuid: uuid, host: "2001:67c:4e8:f002::a", port: 80)
        XCTAssertEqual(v6.count, 38); XCTAssertEqual(v6[21], 3)
        XCTAssertEqual(Array(v6.suffix(4)), [0,0,0,10])
        let domain = try FamilyVLESS.request(uuid: uuid, host: "dc.example", port: 5222)
        XCTAssertEqual(Array(domain.suffix(12)), [2,10] + Array("dc.example".utf8))
        XCTAssertNotEqual(v4, try FamilyVLESS.request(uuid: uuid, host: "149.154.175.50", port: 443))
        for host in ["", "bad host", "host/path", "a..b", "a\u{0}b", "[::1]", "fe80::1%en0", "-label.example"] {
            XCTAssertThrowsError(try FamilyVLESS.request(uuid: uuid, host: host, port: 443))
        }
        XCTAssertThrowsError(try FamilyVLESS.request(uuid: uuid, host: "localhost", port: 0))
    }

    func testResponseHeaderSplitAtEveryByteAndCoalescedPayload() throws {
        let payload = Data((0..<200000).map { UInt8($0 % 251) })
        let stream = Data([0,3,11,22,33]) + payload
        for split in 0...8 {
            var decoder = FamilyVLESSResponseDecoder()
            var output = try decoder.consume(Data(stream.prefix(split)))
            output.append(try decoder.consume(Data(stream.dropFirst(split))))
            XCTAssertEqual(output, payload); XCTAssertNoThrow(try decoder.finish())
        }
        var decoder = FamilyVLESSResponseDecoder(), output = Data()
        for byte in stream.prefix(16) { output.append(try decoder.consume(Data([byte]))) }
        output.append(try decoder.consume(Data(stream.dropFirst(16))))
        XCTAssertEqual(output, payload)
        var invalid = FamilyVLESSResponseDecoder()
        XCTAssertThrowsError(try invalid.consume(Data([1,0])))
        var truncated = FamilyVLESSResponseDecoder()
        _ = try truncated.consume(Data([0,3,1]))
        XCTAssertThrowsError(try truncated.finish())
    }

    func testExactReadPreservesTailBeyond64KiBBeforeEOF() throws {
        let body = Data((0..<262147).map { UInt8($0 % 251) })
        let reads = FamilyReadBuffer()
        XCTAssertTrue(try reads.read(length: 4, tag: 1).isEmpty)
        let first = try reads.append(Data([1,2,3,4]) + body)
        XCTAssertTrue(first.contains(.data(Data([1,2,3,4]), tag: 1)))
        XCTAssertTrue(reads.end().isEmpty, "EOF must wait for MTProto's next body read")
        let final = try reads.read(length: body.count, tag: 2)
        XCTAssertEqual(final, [.partial(body.count, tag: 2), .data(body, tag: 2), .closed(nil)])
        XCTAssertTrue(reads.terminal); XCTAssertEqual(reads.bufferedBytes, 0)
    }

    func testPartialProgressSlowFragmentsAndIncompleteEOF() throws {
        let reads = FamilyReadBuffer()
        _ = try reads.read(length: 100, tag: 3)
        for _ in 0..<9 { XCTAssertEqual(try reads.append(Data(repeating: 7, count: 10)), [.partial(10, tag: 3)]) }
        XCTAssertEqual(reads.end(), [.closed(.truncatedRead)])
        let bounded = FamilyReadBuffer(limit: 4)
        _ = try bounded.append(Data([1,2,3,4]))
        XCTAssertThrowsError(try bounded.append(Data([5])))
        XCTAssertThrowsError(try FamilyReadBuffer().read(length: 0, tag: 0))
        XCTAssertThrowsError(try FamilyReadBuffer().read(length: FamilyVLESS.maximumMessage + 1, tag: 0))
    }

    func testReadRequestsAndDataCanCrossArbitraryBoundaries() throws {
        let reads = FamilyReadBuffer()
        _ = try reads.read(length: 3, tag: 10); _ = try reads.read(length: 2, tag: 11)
        var events = try reads.append(Data([1]))
        events += try reads.append(Data([2,3,4,5]))
        XCTAssertEqual(events, [.partial(1, tag: 10), .partial(2, tag: 10), .data(Data([1,2,3]), tag: 10), .partial(2, tag: 11), .data(Data([4,5]), tag: 11)])
        XCTAssertEqual(reads.end(error: .tlsOrNetworkFailure), [.closed(.tlsOrNetworkFailure)])
    }

    func testDataSlicesAndManyConsumedChunksDoNotCorruptOffsets() throws {
        let storage = Data((0..<128).map(UInt8.init))
        let slice = storage[64..<128]
        let reads = FamilyReadBuffer()
        let writes = FamilyWriteBuffer(chunkSize: 7)
        for tag in 0..<200 {
            _ = try reads.read(length: slice.count, tag: tag)
            XCTAssertTrue(try reads.append(slice).contains(.data(Data(slice), tag: tag)))
            try writes.append(slice)
            var result = Data()
            while let chunk = writes.next() { result.append(chunk); writes.acknowledge() }
            XCTAssertEqual(result, Data(slice))
        }
    }

    func testWritesStayBoundedUntilAsyncCompletionAndPreserveAllBytes() throws {
        let writes = FamilyWriteBuffer(limit: 300000, chunkSize: 65536)
        let input = Data((0..<262147).map { UInt8($0 % 251) })
        try writes.append(input)
        let first = writes.next()!
        XCTAssertNil(writes.next(), "Only one WS send may be in flight")
        XCTAssertEqual(writes.queuedBytes, input.count)
        XCTAssertThrowsError(try writes.append(Data(repeating: 0, count: 40000)))
        var result = first; writes.acknowledge()
        while let next = writes.next() { result.append(next); writes.acknowledge() }
        XCTAssertEqual(result, input); XCTAssertEqual(writes.queuedBytes, 0)
        try writes.append(Data([1,2])); _ = writes.next(); writes.clear(); writes.acknowledge()
        XCTAssertNil(writes.next()); XCTAssertEqual(writes.queuedBytes, 0)
    }

    func testLeaseRenewRotateRevokeAndSleepDeadline() {
        var now: UInt64 = 100
        let lease = FamilyTransportLease(clock: { now })
        var invalidations = 0
        XCTAssertNil(lease.openFlow(onInvalidation: { invalidations += 1 }))
        XCTAssertTrue(lease.authorize(uuid: uuid, validFor: 75))
        let ticket = lease.openFlow(onInvalidation: { invalidations += 1 })!
        now += 60_000_000_000
        XCTAssertTrue(lease.authorize(uuid: uuid, validFor: 75)); XCTAssertTrue(lease.permits(ticket))
        XCTAssertEqual(invalidations, 0)
        now += 75_000_000_001 // Device suspended: callbacks/timers have not run, but next I/O must refuse.
        XCTAssertFalse(lease.permits(ticket)); XCTAssertEqual(invalidations, 1)
        XCTAssertTrue(lease.authorize(uuid: uuid, validFor: 75)); XCTAssertFalse(lease.permits(ticket))
        let replacement = lease.openFlow(onInvalidation: { invalidations += 1 })!
        XCTAssertTrue(lease.authorize(uuid: UUID(), validFor: 75)); XCTAssertFalse(lease.permits(replacement))
        XCTAssertEqual(invalidations, 2)
        _ = lease.openFlow(onInvalidation: { invalidations += 1 })!
        lease.invalidate(); lease.invalidate(); XCTAssertEqual(invalidations, 3)
        for duration in [0.0, -1, 75.1, .infinity, .nan] { XCTAssertFalse(lease.authorize(uuid: uuid, validFor: duration)) }
    }

    func testNormalCloseUnregistersWithoutDroppingAlreadyQueuedTail() {
        let lease = FamilyTransportLease(clock: { 1 })
        XCTAssertTrue(lease.authorize(uuid: uuid, validFor: 75))
        let ticket = lease.openFlow(onInvalidation: { XCTFail("Closed stream should not be invalidated") })!
        lease.closeFlow(ticket)
        XCTAssertTrue(lease.permits(ticket), "Queued data preceding the normal disconnect remains deliverable")
        lease.invalidate(); XCTAssertFalse(lease.permits(ticket))
    }
}
