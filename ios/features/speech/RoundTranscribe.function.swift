    private func transcribe() {
        guard familyLocalSpeechEnabled, let item = self.item, item.message.id.namespace == Namespaces.Message.Cloud else { return }
        if case .inProgress = self.audioTranscriptionState { return }
        if case .expanded = self.audioTranscriptionState {
            self.audioTranscriptionState = .collapsed; self.isWaitingForCollapse = true
            self.requestUpdateLayout(true); self.updateTranscriptionExpanded?(.collapsed); return
        }
        if case .success? = transcribedText(message: EngineMessage(item.message)) {
            self.audioTranscriptionState = .expanded; self.requestUpdateLayout(true)
            self.updateTranscriptionExpanded?(.expanded); return
        }
        let id = item.message.id
        let context = item.context
        let locale = context.sharedContext.currentPresentationData.with { $0 }.strings.baseLanguageCode
        self.audioTranscriptionState = .inProgress; self.requestUpdateLayout(true)
        self.updateTranscriptionExpanded?(.inProgress)
        let signal: Signal<FamilySpeechResult, NoError> = context.engine.data.get(TelegramEngine.EngineData.Item.Messages.Message(id: id))
        |> mapToSignal { message -> Signal<FamilySpeechResult, NoError> in
            guard let message, let file = message.media.first(where: { $0 is TelegramMediaFile }) as? TelegramMediaFile else { return .single(.failure(.missingFile)) }
            return context.engine.resources.data(id: EngineMediaResource.Id(file.resource.id))
            |> take(1)
            |> mapToSignal { data -> Signal<FamilySpeechResult, NoError> in
                guard data.isComplete else { return .single(.failure(.missingFile)) }
                return familyTranscribeAudio(path: data.path, appLocale: locale)
            }
        }
        self.transcribeDisposable = (signal |> timeout(120, queue: .mainQueue(), alternate: .single(.failure(.timeout))) |> deliverOnMainQueue).startStrict(next: { [weak self] result in
            guard let self, self.item?.message.id == id else { return }
            switch result {
            case let .success(text):
                let _ = context.engine.messages.storeLocallyTranscribedAudio(messageId: id, text: text, isFinal: true, error: nil).startStandalone()
                self.audioTranscriptionState = .expanded
            case let .failure(error):
                self.audioTranscriptionState = .collapsed
                familyPresentSpeechError(error, from: self.view)
            }
            self.updateTranscriptionExpanded?(self.audioTranscriptionState); self.requestUpdateLayout(true)
        }, completed: { [weak self] in self?.transcribeDisposable?.dispose(); self?.transcribeDisposable = nil })
    }
