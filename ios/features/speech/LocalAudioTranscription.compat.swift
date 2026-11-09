import SwiftSignalKit

public struct LocallyTranscribedAudio {
    public var text: String
    public var isFinal: Bool
}

// Preserve the official module's public API while removing its permissive/network-capable implementation.
public func transcribeAudio(path: String, appLocale: String) -> Signal<LocallyTranscribedAudio?, NoError> {
    return familyTranscribeAudio(path: path, appLocale: appLocale) |> map { result -> LocallyTranscribedAudio? in
        switch result {
        case let .success(text): return LocallyTranscribedAudio(text: text, isFinal: true)
        case .failure: return nil
        }
    }
}
