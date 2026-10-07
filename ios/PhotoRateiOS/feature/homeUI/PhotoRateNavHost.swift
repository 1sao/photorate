//
//  PhotoRateNavHost.swift
//  PhotoRateiOS
//
//  Created by Ihor Pakharenko on 6. 10. 2026.
//

import SwiftUI

enum Route: Hashable {
    case home
    case imageDetails(uri: String)
    case config
}

struct PhotoRateNavHost: View {
    @State private var path: [Route] = []

    var body: some View {
        NavigationStack(path: $path) {
            home
                .navigationDestination(for: Route.self) { destination in
                    switch destination {
                    case .home: home
                    case .imageDetails(let uri):
                        ImageDetailsScreen(uri: uri, onBack: onBack)
                    case .config:
                        ConfigScreen(onBack: onBack, onPurgeAndRescan: {})
                    }
                }
        }
    }

    private var home: some View {
        HomeScreen(
            onOpenSettings: { path.append(.config) },
            onOpenImage: { uri in path.append(.imageDetails(uri: uri)) }
        )
    }

    private func onBack() {
        path.removeLast()
    }
}
