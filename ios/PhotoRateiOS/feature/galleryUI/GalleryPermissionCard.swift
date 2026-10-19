//
//  GalleryPermissionCard.swift
//  PhotoRateiOS
//
//  Created by Ihor Pakharenko on 16. 10. 2026..
//

import SwiftUI

struct GalleryPermissionCard: View {
    @Environment(\.theme) private var theme

    let isPartialAccess: Bool
    let onPrimaryAction: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(isPartialAccess ? "Manage photo access" : "Allow photo access")
                .font(.title3)
            Text(
                isPartialAccess
                    ? "Photo Rate can only see the photos you selected. You can change which photos are visible at any time. Your photos never leave your device."
                    : "Photo Rate needs access to your gallery to look for scored images. Your photos never leave your device."
            )
                .font(.body)
            Button(
                isPartialAccess ? "Manage selection" : "Grant access",
                action: onPrimaryAction
            )
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.top, 8)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.colorScheme.primaryContainer)
        .clipShape(theme.shapes.extraLarge)
    }
}

#Preview("Access not granted") {
    PhotoRatePreview {
        GalleryPermissionCard(isPartialAccess: false, onPrimaryAction: {})
            .padding()
    }
}

#Preview("Partial access") {
    PhotoRatePreview {
        GalleryPermissionCard(isPartialAccess: true, onPrimaryAction: {})
            .padding()
    }
}
