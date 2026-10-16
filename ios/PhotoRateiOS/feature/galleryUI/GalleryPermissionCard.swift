//
//  GalleryPermissionCard.swift
//  PhotoRateiOS
//
//  Created by Ihor Pakharenko on 16. 10. 2026..
//

import SwiftUI

// TODO: implement photo-permission requesting and partial-access handling.
struct GalleryPermissionCard: View {
    @Environment(\.theme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Allow photo access")
                .font(.title3)
            Text(
                "Photo Rate needs access to your gallery to look for scored images. Your photos never leave your device."
            )
                .font(.body)
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.colorScheme.primaryContainer)
        .clipShape(theme.shapes.extraLarge)
    }
}

#Preview {
    PhotoRatePreview {
        GalleryPermissionCard()
            .padding()
    }
}
