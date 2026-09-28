//
//  ScanStatusCard.swift
//  PhotoRateiOS
//
//  Created by Ihor Pakharenko on 28. 9. 2026..
//

import SwiftUI
import shared

private enum ScanCounterColors {
    static let pending = Color(red: 0x9E / 255.0, green: 0x9E / 255.0, blue: 0x9E / 255.0)
    static let detected = Color(red: 0x4C / 255.0, green: 0xAF / 255.0, blue: 0x50 / 255.0)
}

struct ScanStatusCard: View {
    let statusCounts: GalleryStatusCounts

    @Environment(\.theme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Scan status")
                .font(.headline)
            Text("\(statusCounts.total) photos total")
                .font(.subheadline)
            HStack(spacing: 16) {
                ScanCounter(title: "Pending", count: statusCounts.pending, color: ScanCounterColors.pending)
                ScanCounter(title: "Detected", count: statusCounts.detected, color: ScanCounterColors.detected)
            }
            .padding(.top, 12)
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(theme.colorScheme.surfaceBright)
        .clipShape(theme.shapes.largeIncreased)
    }
}

private struct ScanCounter: View {
    let title: String
    let count: Int64
    let color: Color

    @Environment(\.theme) private var theme

    var body: some View {
        VStack(spacing: 0) {
            Text(title)
                .font(.subheadline)
                .foregroundStyle(theme.colorScheme.onSurfaceVariant)
            Text("\(count)")
                .font(.title.bold())
                .foregroundStyle(color)
                .contentTransition(.numericText(value: Double(count)))
                .animation(.default, value: count)
        }
        .frame(maxWidth: .infinity)
    }
}

#Preview {
    PhotoRatePreview {
        ScanStatusCardPreviewHost()
    }
}

private struct ScanStatusCardPreviewHost: View {
    @State private var counts = GalleryStatusCounts(pending: 120, done: 0, failed: 0, detected: 0)

    var body: some View {
        ScanStatusCard(statusCounts: counts)
            .padding()
            .task {
                for await next in previewGalleryStatusStream(totalPhotos: 120) {
                    counts = next
                }
            }
    }
}

private func previewGalleryStatusStream(
    totalPhotos: Int64 = 120,
    processingDelay: Duration = .milliseconds(520)
) -> AsyncStream<GalleryStatusCounts> {
    AsyncStream { continuation in
        Task {
            var done: Int64 = 0
            var failed: Int64 = 0
            continuation.yield(GalleryStatusCounts(pending: totalPhotos, done: 0, failed: 0, detected: 0))
            while done + failed < totalPhotos {
                try? await Task.sleep(for: processingDelay)
                if Bool.random() ? false : Int.random(in: 0..<12) == 0 {
                    failed += 1
                } else {
                    done += 1
                }
                continuation.yield(
                    GalleryStatusCounts(
                        pending: totalPhotos - done - failed,
                        done: done,
                        failed: failed,
                        detected: done
                    )
                )
            }
            continuation.finish()
        }
    }
}
