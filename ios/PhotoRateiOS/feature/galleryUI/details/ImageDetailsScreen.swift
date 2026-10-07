//
//  ImageDetailsScreen.swift
//  PhotoRateiOS
//
//  Created by Ihor Pakharenko on 6. 10. 2026.
//

import SwiftUI

struct ImageDetailsScreen: View {
    let uri: String
    let onBack: () -> Void

    var body: some View {
        Text("ImageDetails")
    }
}

#Preview {
    PhotoRatePreview {
        ImageDetailsScreen(uri: "content://preview/1", onBack: {})
    }
}
