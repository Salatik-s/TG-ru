import Foundation
import AVFoundation
import Speech
import UIKit
import SwiftSignalKit

public var familyLocalSpeechEnabled: Bool { UserDefaults.standard.bool(forKey: "family.features.localSpeech") }

public enum FamilySpeechFailure: Error {
    case unavailable, permission, missingFile, tooLong, invalidAudio, timeout, empty, busy
    public var text: String {
        switch self {
        case .unavailable: return "Распознавание на устройстве недоступно для этого языка."
        case .permission: return "Разрешите распознавание речи в настройках iOS."
        case .missingFile: return "Сначала скачайте сообщение полностью."
        case .tooLong: return "Можно расшифровать запись до 5 минут и 16 МБ."
        case .invalidAudio: return "Не удалось прочитать звук в этом файле."
        case .timeout: return "Распознавание заняло слишком много времени. Попробуйте ещё раз."
        case .empty: return "Речь не распознана."
        case .busy: return "Дождитесь завершения текущей расшифровки."
        }
    }
}

public enum FamilySpeechResult {
    case success(String)
    case failure(FamilySpeechFailure)
}

public func familyPresentSpeechError(_ failure: FamilySpeechFailure, from view: UIView) {
    guard var controller = view.window?.rootViewController else { return }
    while let presented = controller.presentedViewController { controller = presented }
    guard !(controller is UIAlertController) else { return }
    let alert = UIAlertController(title: "Расшифровка на устройстве", message: failure.text, preferredStyle: .alert)
    alert.addAction(UIAlertAction(title: "OK", style: .default))
    controller.present(alert, animated: true)
}

/// Accepts an already downloaded AAC/MP4/WAV file. Voice Opus is converted by Telegram's existing decoder.
/// No recording, networking, server transcription, transcript logging or fallback recognizer.
public func familyTranscribeAudio(path: String, appLocale: String) -> Signal<FamilySpeechResult, NoError> {
    return Signal { subscriber in
        guard #available(iOS 15.0, *) else {
            subscriber.putNext(.failure(.unavailable)); subscriber.putCompletion()
            return EmptyDisposable
        }
        let job = FamilySpeechJob(path: path, locale: appLocale) { result in
            subscriber.putNext(result); subscriber.putCompletion()
        }
        DispatchQueue.main.async { job.start() }
        return ActionDisposable { job.cancel() }
    }
}

@available(iOS 15.0, *)
private final class FamilySpeechJob {
    // Accessed only on the main queue. One recognizer task at a time, without retaining old chat cells.
    private static weak var active: FamilySpeechJob?
    private let source: URL
    private let locale: String
    private let completion: (FamilySpeechResult) -> Void
    private let lock = NSLock()
    private var cancelled = false
    private var finished = false
    private var speech: SFSpeechRecognitionTask?
    private var recognizer: SFSpeechRecognizer?
    private var deadline: DispatchWorkItem?
    private let temporary = FileManager.default.temporaryDirectory.appendingPathComponent("family-speech-" + UUID().uuidString + ".wav")

    init(path: String, locale: String, completion: @escaping (FamilySpeechResult) -> Void) {
        self.source = URL(fileURLWithPath: path); self.locale = locale; self.completion = completion
    }
    private var isCancelled: Bool {
        lock.lock(); defer { lock.unlock() }; return cancelled
    }
    func cancel() {
        lock.lock(); cancelled = true; lock.unlock()
        DispatchQueue.main.async { self.finish(nil) }
    }
    func start() {
        guard !isCancelled else { return }
        guard FamilySpeechJob.active == nil else { finish(.failure(.busy)); return }
        FamilySpeechJob.active = self
        let timeout = DispatchWorkItem { [weak self] in self?.finish(.failure(.timeout)) }
        deadline = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 90, execute: timeout)
        guard let values = try? source.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey]), values.isRegularFile == true else {
            finish(.failure(.missingFile)); return
        }
        guard let size = values.fileSize, size > 0, size <= 16 * 1024 * 1024 else {
            finish(.failure(.tooLong)); return
        }
        SFSpeechRecognizer.requestAuthorization { [weak self] status in
            DispatchQueue.main.async {
                guard let self, !self.finished, !self.isCancelled else { return }
                guard status == .authorized else { self.finish(.failure(.permission)); return }
                let identifier = self.locale == "ru" ? "ru-RU" : self.locale == "en" ? "en-US" : self.locale
                guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: identifier)),
                    recognizer.isAvailable, recognizer.supportsOnDeviceRecognition else {
                    self.finish(.failure(.unavailable)); return
                }
                self.recognizer = recognizer
                Task {
                    do {
                        let asset = AVURLAsset(url: self.source)
                        let duration = try await asset.load(.duration).seconds
                        guard duration.isFinite, duration > 0, duration <= 300 else { throw FamilySpeechFailure.tooLong }
                        guard let track = (try await asset.loadTracks(withMediaType: .audio)).first else { throw FamilySpeechFailure.invalidAudio }
                        // Decode on a dedicated queue; never block the chat or Speech callback queue.
                        DispatchQueue.global(qos: .userInitiated).async {
                            do {
                                try self.writePCM(asset: asset, track: track)
                                DispatchQueue.main.async { self.recognize(using: recognizer) }
                            } catch {
                                try? FileManager.default.removeItem(at: self.temporary)
                                DispatchQueue.main.async { self.finish(.failure(error as? FamilySpeechFailure ?? .invalidAudio)) }
                            }
                        }
                    } catch {
                        DispatchQueue.main.async { self.finish(.failure(error as? FamilySpeechFailure ?? .invalidAudio)) }
                    }
                }
            }
        }
    }
    private func writePCM(asset: AVAsset, track: AVAssetTrack) throws {
        guard !isCancelled else { throw FamilySpeechFailure.timeout }
        let reader = try AVAssetReader(asset: asset)
        defer { reader.cancelReading() }
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
            AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: 16000,
            AVNumberOfChannelsKey: 1, AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false
        ])
        output.alwaysCopiesSampleData = false
        guard reader.canAdd(output) else { throw FamilySpeechFailure.invalidAudio }
        reader.add(output)
        guard reader.startReading() else { throw FamilySpeechFailure.invalidAudio }
        var pcm = Data()
        let end = ProcessInfo.processInfo.systemUptime + 30
        while let sample = output.copyNextSampleBuffer() {
            guard !isCancelled, ProcessInfo.processInfo.systemUptime < end else { throw FamilySpeechFailure.timeout }
            guard let block = CMSampleBufferGetDataBuffer(sample) else { throw FamilySpeechFailure.invalidAudio }
            let length = CMBlockBufferGetDataLength(block)
            guard length > 0, length % 2 == 0, pcm.count + length <= 9_600_000 else { throw FamilySpeechFailure.tooLong }
            var chunk = Data(count: length)
            let result = chunk.withUnsafeMutableBytes { ptr in
                CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: ptr.baseAddress!)
            }
            guard result == kCMBlockBufferNoErr else { throw FamilySpeechFailure.invalidAudio }
            pcm.append(chunk)
        }
        guard reader.status == .completed, !pcm.isEmpty, !isCancelled else { throw FamilySpeechFailure.invalidAudio }
        var wav = Data()
        func text(_ value: String) { wav.append(contentsOf: value.utf8) }
        func u32(_ value: UInt32) { var x = value.littleEndian; withUnsafeBytes(of: &x) { wav.append(contentsOf: $0) } }
        func u16(_ value: UInt16) { var x = value.littleEndian; withUnsafeBytes(of: &x) { wav.append(contentsOf: $0) } }
        text("RIFF"); u32(UInt32(pcm.count + 36)); text("WAVEfmt "); u32(16); u16(1); u16(1)
        u32(16000); u32(32000); u16(2); u16(16); text("data"); u32(UInt32(pcm.count)); wav.append(pcm)
        try wav.write(to: temporary, options: [.atomic, .completeFileProtection])
        if isCancelled { try? FileManager.default.removeItem(at: temporary); throw FamilySpeechFailure.timeout }
    }
    private func recognize(using recognizer: SFSpeechRecognizer) {
        guard !finished, !isCancelled, recognizer.isAvailable, recognizer.supportsOnDeviceRecognition else {
            try? FileManager.default.removeItem(at: temporary); finish(.failure(.unavailable)); return
        }
        let request = SFSpeechURLRecognitionRequest(url: temporary)
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = false
        if #available(iOS 16.0, *) { request.addsPunctuation = true }
        speech = recognizer.recognitionTask(with: request) { [weak self] result, error in
            DispatchQueue.main.async {
                guard let self, !self.finished else { return }
                if let result, result.isFinal {
                    let text = result.bestTranscription.formattedString.trimmingCharacters(in: .whitespacesAndNewlines)
                    self.finish(text.isEmpty ? .failure(.empty) : .success(String(text.prefix(65536))))
                } else if error != nil { self.finish(.failure(.unavailable)) }
            }
        }
    }
    private func finish(_ result: FamilySpeechResult?) {
        guard !finished else { return }; finished = true
        lock.lock(); cancelled = true; lock.unlock()
        deadline?.cancel(); deadline = nil; speech?.cancel(); speech = nil; recognizer = nil
        if FamilySpeechJob.active === self { FamilySpeechJob.active = nil }
        try? FileManager.default.removeItem(at: temporary)
        if let result { completion(result) }
    }
}
