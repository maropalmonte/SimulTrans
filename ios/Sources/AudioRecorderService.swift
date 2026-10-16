import AVFoundation

/// Graba audio del micrófono y lo guarda como .wav (16 kHz, mono, 16 bits),
/// el formato que el decodificador de LiteRT-LM sabe leer. Usa AVAudioRecorder
/// (el método más sencillo y fiable de Apple): ya no hay motor de audio, ni
/// "taps", ni conversión manual que puedan fallar en un iPhone real.
final class AudioRecorderService {
    private var recorder: AVAudioRecorder?
    private(set) var isRecording = false

    func startRecording(to url: URL) throws {
        Diagnostico.paso("Grabación: configurando sesión de audio")
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
        try session.setActive(true)

        try? FileManager.default.removeItem(at: url)
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatLinearPCM),
            AVSampleRateKey: 16000,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false
        ]
        Diagnostico.paso("Grabación: creando grabador")
        let r = try AVAudioRecorder(url: url, settings: settings)
        guard r.prepareToRecord(), r.record() else {
            throw NSError(domain: "AudioRecorderService", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "No se pudo iniciar la grabación"])
        }
        recorder = r
        isRecording = true
        Diagnostico.paso("Grabación: en marcha")
    }

    func stopRecording() {
        recorder?.stop()
        recorder = nil
        isRecording = false
        Diagnostico.paso("Grabación: detenida")
    }
}
