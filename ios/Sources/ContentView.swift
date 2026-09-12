import SwiftUI

struct ContentView: View {
    var body: some View {
        VStack(spacing: 16) {
            Text("👋 SimulTrans")
                .font(.largeTitle)
                .bold()
            Text("Paso 1: el esqueleto de la app compila correctamente.")
                .multilineTextAlignment(.center)
                .foregroundColor(.secondary)
                .padding()
        }
        .padding()
    }
}

#Preview {
    ContentView()
}
