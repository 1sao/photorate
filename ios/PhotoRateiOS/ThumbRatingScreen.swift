import SwiftUI
import shared

struct ThumbRatingScreen: View {
    @State private var results: String = "Tap to test"
    @State private var lastResult: String = ""

    private let recognizer = KotlinDependencies.shared.getGestureRecognizer()

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
        var lines: [String] = ["=== Running tests ==="]

        for name in ["1", "3", "5"] {
            lines.append("--- Testing \(name).png ---")

            guard let uiImage = UIImage(named: name) else {
                lines.append("  UIImage is nil")
                continue
            }

            guard let cgImage = uiImage.cgImage else {
                lines.append("  CGImage is nil")
                continue
            }

            let w = cgImage.width
            let h = cgImage.height
            lines.append("  Size: \(w)x\(h)")

            let byteCount = w * h * 4

            guard let context = CGContext(
                data: nil,
                width: w,
                height: h,
                bitsPerComponent: 8,
                bytesPerRow: w * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else {
                lines.append("  CGContext failed")
                continue
            }

            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: w, height: h))

            guard let pixelData = context.data else {
                lines.append("  No pixel data")
                continue
            }

            let byteArray = KotlinByteArray(size: Int32(byteCount))
            let ptr = pixelData.assumingMemoryBound(to: UInt8.self)
            for i in 0..<byteCount {
                byteArray.set(index: Int32(i), value: Int8(bitPattern: ptr[i]))
            }

            let result = recognizer.recognize(imageData: byteArray, width: Int32(w), height: Int32(h))

            if let success = result as? GestureResult.Success {
                let rating = Int(success.thumbRating)
                let hands = success.landmarks.count
                let gesture = success.gestures.first?.first?.categoryName ?? "none"
                lines.append("  Result: rating=\(rating) hands=\(hands) gesture=\(gesture)")

                if let firstHand = success.landmarks.first, firstHand.count >= 5 {
                    let wrist = firstHand[0]
                    let tip = firstHand[4]
                    let dx = tip.x - wrist.x
                    let dy = tip.y - wrist.y
                    let angle = atan2(dy, dx) * 180.0 / .pi
                    lines.append("  Wrist: (\(wrist.x), \(wrist.y))")
                    lines.append("  ThumbTip: (\(tip.x), \(tip.y))")
                    lines.append("  Angle: \(angle) deg")
                }
            } else if let error = result as? GestureResult.Error {
                lines.append("  Error: \(error.message)")
            } else {
                lines.append("  Empty result")
            }
        }

        lines.append("=== Done ===")
        results = lines.joined(separator: "\n")

        // Write to Documents for host access
        if let docsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first {
            let fileUrl = docsDir.appendingPathComponent("recognizer_results.txt")
            try? results.write(to: fileUrl, atomically: true, encoding: .utf8)
        }
    }
}
