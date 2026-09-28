//
//  PhotoRatePreview.swift
//  PhotoRateiOS
//
//  Created by Ihor Pakharenko on 25. 9. 2026..
//

import SwiftUI

struct PhotoRatePreview<Content: View>: View {
    let content: () -> Content
    var body: some View {
        PhotoRateTheme {
            content()
        }
        .environment(\.phImageLoader, PreviewPHImageLoader())
    }
}
