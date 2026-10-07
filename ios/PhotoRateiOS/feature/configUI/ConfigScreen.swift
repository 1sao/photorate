//
//  ConfigScreen.swift
//  PhotoRateiOS
//
//  Created by Ihor Pakharenko on 6. 10. 2026.
//

import SwiftUI

struct ConfigScreen: View {
    let onBack: () -> Void
    let onPurgeAndRescan: () -> Void

    var body: some View {
        Text("Config")
    }
}

#Preview {
    PhotoRatePreview {
        ConfigScreen(onBack: {}, onPurgeAndRescan: {})
    }
}
