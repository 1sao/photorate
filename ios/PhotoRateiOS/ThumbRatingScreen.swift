import SwiftUI
import shared

struct ThumbRatingScreen: View {
    @State private var results: String = "Tap to test"

    var body: some View {
        VStack(spacing: 16) {
            Text("Thumb Rating Test")
                .font(.title)

            Button("Run Tests") {
                runTests()
            }
            .buttonStyle(.borderedProminent)

            ScrollView {
                Text(results)
                    .font(.system(.body, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
            }

            Spacer()
        }
        .padding()
    }

    private func runTests() {
        results = "Recognizer test API removed — pipeline moved to shared Koin graph"
    }
}
