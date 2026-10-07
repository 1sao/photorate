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

//TODO: Move to a proper place
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
                Section {
                    ForEach(state.gallery.imagesState.detections, id: \.self) {
                        item in
                        GalleryItem(item: item, onClick: onOpenImage)
                            .aspectRatio(1, contentMode: .fit)
                    }
                } header: {
                    ScanStatusCard(
                        statusCounts: state.gallery.imagesState.status
                    )
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
                }

            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

#Preview("Gallery with thumbnails") {
    PhotoRatePreview {
        HomeScreenContent(
            state: HomeScreenUiState(
                gallery: GalleryUiState(
                    imagesState: GalleryUiState.ImagesState(
                        status: GalleryStatusCounts(
                            pending: 15,
                            done: 0,
                            failed: 0,
                            detected: 98
                        ),
                        detections: [
                            GalleryImageItem(
                                uri: "content://preview/a",
                                score: Score.five,
                                scannedAt: nil
                            ),
                            GalleryImageItem(
                                uri: "content://preview/b",
                                score: Score.one,
                                scannedAt: nil
                            ),
                            GalleryImageItem(
                                uri: "content://preview/c",
                                score: Score.four,
                                scannedAt: nil
                            ),
                            GalleryImageItem(
                                uri: "content://preview/d",
                                score: Score.two,
                                scannedAt: nil
                            ),
                        ],
                        uncertainDetections: [
                            GalleryImageItem(
                                uri: "content://preview/u1",
                                score: Score.three,
                                scannedAt: nil
                            ),
                            GalleryImageItem(
                                uri: "content://preview/u2",
                                score: Score.four,
                                scannedAt: nil
                            ),
                        ]
                    )
                ),
                search: previewSearchState()
            ),
            onOpenSettings: {},
            onOpenImage: { _ in },
            onIntent: { _ in }
        )
    }
}

#Preview("Empty (permission needed)") {
    PhotoRatePreview {
        HomeScreenContent(
            state: HomeScreenUiState(
                gallery: GalleryUiState(
                    imagesState: GalleryUiState.ImagesState(
                        status: GalleryStatusCounts(
                            pending: 0,
                            done: 0,
                            failed: 0,
                            detected: 0
                        ),
                        detections: [],
                        uncertainDetections: []
                    )
                ),
                search: previewSearchState()
            ),
            onOpenSettings: {},
            onOpenImage: { _ in },
            onIntent: { _ in }
        )
    }
}

private func previewSearchState() -> SearchUiState {
    SearchUiState(
        queryResults: nil,
        pendingQuery: Query.companion.INITIAL,
        recentSearches: [],
        isInDevMode: false
    )
}
