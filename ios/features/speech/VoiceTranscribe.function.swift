    private func transcribe() {
        guard familyLocalSpeechEnabled, let context = self.context, let message = self.message else { return }
        if case .inProgress = self.audioTranscriptionState { return }
        if case .expanded = self.audioTranscriptionState {
            self.audioTranscriptionState = .collapsed; self.isWaitingForCollapse = true
            self.requestUpdateLayout(true); self.updateTranscriptionExpanded?(.collapsed); return
        }
        if case .success? = transcribedText(message: EngineMessage(message)) {
            self.audioTranscriptionState = .expanded; self.requestUpdateLayout(true)
            self.updateTranscriptionExpanded?(.expanded); return
        }
        let locale = context.sharedContext.currentPresentationData.with { $0 }.strings.baseLanguageCode
        self.audioTranscriptionState = .inProgress; self.requestUpdateLayout(true)
        let signal: Signal<FamilySpeechResult, NoError> = context.engine.data.get(TelegramEngine.EngineData.Item.Messages.Message(id: message.id))
        |> mapToSignal { fetched -> Signal<FamilySpeechResult, NoError> in
            guard let fetched, let file = fetched.media.first(where: { $0 is TelegramMediaFile }) as? TelegramMediaFile else { return .single(.failure(.missingFile)) }
            return context.engine.resources.data(id: EngineMediaResource.Id(file.resource.id))
            |> take(1)
            |> mapToSignal { data -> Signal<FamilySpeechResult, NoError> in
                guard data.isComplete else { return .single(.failure(.missingFile)) }
                guard let size = try? URL(fileURLWithPath: data.path).resourceValues(forKeys: [.fileSizeKey]).fileSize,
                      size > 0, size <= 16 * 1024 * 1024, (file.duration ?? 0) <= 300 else { return .single(.failure(.tooLong)) }
                if file.isInstantVideo { return familyTranscribeAudio(path: data.path, appLocale: locale) }
                let temporary = NSTemporaryDirectory() + "/family-opus-" + UUID().uuidString + ".m4a"
                return (convertOpusToAAC(sourcePath: data.path, allocateTempFile: { temporary })
                    |> mapToSignal { path -> Signal<FamilySpeechResult, NoError> in
                        guard let path else { return .single(.failure(.invalidAudio)) }
                        return familyTranscribeAudio(path: path, appLocale: locale)
                    }) |> afterDisposed { try? FileManager.default.removeItem(atPath: temporary) }
            }
        }
        self.transcribeDisposable = (signal |> timeout(120, queue: .mainQueue(), alternate: .single(.failure(.timeout))) |> deliverOnMainQueue).startStrict(next: { [weak self] result in
            guard let self, self.message?.id == message.id else { return }
            switch result {
            case let .success(text):
                let _ = context.engine.messages.storeLocallyTranscribedAudio(messageId: message.id, text: text, isFinal: true, error: nil).startStandalone()
                self.audioTranscriptionState = .expanded; self.updateTranscriptionExpanded?(.expanded)
            case let .failure(error):
                self.audioTranscriptionState = .collapsed
                familyPresentSpeechError(error, from: self.view)
            }
            self.requestUpdateLayout(true)
        }, completed: { [weak self] in self?.transcribeDisposable?.dispose(); self?.transcribeDisposable = nil })
    }
