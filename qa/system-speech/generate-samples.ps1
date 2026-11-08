$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Speech
$synth=New-Object System.Speech.Synthesis.SpeechSynthesizer
$destination=Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$format=New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000,[System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,[System.Speech.AudioFormat.AudioChannel]::Mono)
$samples=@(
 @{Language='ru';Name='synthetic-ru';Text='Это проверка распознавания речи на телефоне. Один, два, три. Два плюс два равно четыре.'},
 @{Language='en';Name='synthetic-en';Text='This is a local speech recognition test. One, two, three. Two plus two equals four.'}
)
foreach($sample in $samples){
 $voice=$synth.GetInstalledVoices() | Where-Object {$_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq $sample.Language} | Select-Object -First 1
 if($null -eq $voice){Write-Output "No installed offline voice for $($sample.Language)";continue}
 $synth.SelectVoice($voice.VoiceInfo.Name)
 $path=Join-Path $destination ($sample.Name+'.wav')
 $synth.SetOutputToWaveFile($path,$format)
 $synth.Speak($sample.Text)
 $synth.SetOutputToNull()
 Write-Output "Created local synthetic $($sample.Language) WAV: $((Get-Item -LiteralPath $path).Length) bytes"
}
$synth.Dispose()
