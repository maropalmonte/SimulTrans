import AVFoundation

/// Graba audio del micrófono y lo guarda como .wav (16 kHz, mono, 16 bits),
/// el formato que el decodificador de LiteRT-LM sabe leer. Equivalente a la
/// grabación con AudioRecord + construcción manual del WAV que hicimos en
/// Android — aquí AVAudioFile se encarga de escribir la cabecera por
/// nosotros, así que el código es bastante más corto.
final class AudioRecorderService {
    private let engine = AVAudioEngine()
    private var audioFile: AVAudioFile?
    private(set) var isRecording = false

    func startRecording(to url: URL) throws {
        let inputNode = engine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: 0)

        guard let outputFormat = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: 16000,
            channels: 1,
            interleaved: true
        ) else {
            throw NSError(domain: "AudioRecorderService", code: 1, userInfo: [NSLocalizedDescriptionKey: "Formato de audio no soportado"])
        }

        guard let converter = AVAudioConverter(from: inputFormat, to: outputFormat) else {
            throw NSError(domain: "AudioRecorderService", code: 2, userInfo: [NSLocalizedDescriptionKey: "No se pudo crear el conversor de audio"])
        }

        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: 16000,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false
        ]
        let file = try AVAudioFile(forWriting: url, settings: settings)
        audioFile = file

        inputNode.installTap(onBus: 0, bufferSize: 2048, format: inputFormat) { buffer, _ in
            let capacidad = AVAudioFrameCount(
                outputFormat.sampleRate * Double(buffer.frameLength) / inputFormat.sampleRate
            ) + 16
            guard let bufferConvertido = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: capacidad) else { return }

            var error: NSError?
            let bloqueEntrada: AVAudioConverterInputBlock = { _, outStatus in
                outStatus.pointee = .haveData
                return buffer
            }
            converter.convert(to: bufferConvertido, error: &error, withInputFrom: bloqueEntrada)
            if error == nil {
                try? file.write(from: bufferConvertido)
            }
        }

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
        try session.setActive(true)

        engine.prepare()
        try engine.start()
        isRecording = true
    }

    func stopRecording() {
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        audioFile = nil
        isRecording = false
    }
}
