//
//  HomeScreen.swift
//  PhotoRateiOS
//
//  Created by Ihor Pakharenko on 28. 9. 2026..
//

import SwiftUI
import shared

struct HomeScreen: View {
    let onOpenSettings: () -> Void
    let onOpenImage: (String) -> Void

    let viewModel: HomeViewModel = inject()

    @State private var showResetDialog = false

    var body: some View {
        Observing(viewModel.uiState) { state in
            ZStack {
                HomeScreenContent(
                    state: state,
                    onOpenSettings: onOpenSettings,
                    onOpenImage: onOpenImage,
                    onIntent: viewModel.onIntent
                )
                    .task {
                        viewModel.onIntent(
                            intent: HomeIntentGalleryPermissionGranted()
                        )
                    }
            }
        }
    }
}

func inject<T: AnyObject>() -> T {
    return koin.get(clazz: getKotlinClass(objCClass: T.self)!, qualifier: nil)
        as! T
}

struct HomeScreenContent: View {
    let state: HomeScreenUiState

    let onOpenSettings: () -> Void
    let onOpenImage: (String) -> Void
    let onIntent: (HomeIntent) -> Void

    @State var showResetDialog: Bool = false

    var body: some View {

        ScrollView {
            LazyVGrid(
                columns: [GridItem(.flexible()), GridItem(.flexible())],
                spacing: 2
            ) {
                ScanStatusCard(statusCounts: state.gallery.imagesState.status)
                    .frame(maxWidth: .infinity)
                .onTapGesture {
                    showResetDialog = true
                }
                .confirmationDialog(
                    "Rescan gallery?",
                    isPresented: $showResetDialog,
                    ) {
                    Button("Yes, rescan") {
                        onIntent(HomeIntentGalleryResetRequested())
                    }
                    Button("Cancel", role: .cancel, action: {})
                } message: {
                    Text(
                        "Rescan the whole gallery while keeping scores you assigned manually."
                    )
                        .font(.subheadline)
                        .multilineTextAlignment(.center)
                }

                ForEach(state.gallery.imagesState.detections, id: \.self) { item in
                    GalleryItem(item: item, onClick: onOpenImage)
                        .frame(height: 200)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}
