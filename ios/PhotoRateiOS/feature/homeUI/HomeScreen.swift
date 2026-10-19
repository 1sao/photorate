//
//  HomeScreen.swift
//  PhotoRateiOS
//
//  Created by Ihor Pakharenko on 28. 9. 2026..
//

import Photos
import SwiftUI
import shared

struct HomeScreen: View {
    let onOpenSettings: () -> Void
    let onOpenImage: (String) -> Void

    let viewModel: HomeViewModel = inject()

    @State private var permissions = GalleryPermissionsModel()

    var body: some View {
        Observing(viewModel.uiState) { state in
            ZStack {
                HomeScreenContent(
                    state: state,
                    galleryStatus: permissions.status,
                    onOpenSettings: onOpenSettings,
                    onOpenImage: onOpenImage,
                    onManagePermission: {
                        Task {
                            await permissions.request()
                            syncGalleryIfAccessGranted()
                        }
                    },
                    onIntent: viewModel.onIntent
                )
            }
            .onChange(of: permissions.status, initial: true) { _, _ in
                syncGalleryIfAccessGranted()
            }
        }
    }

    private func syncGalleryIfAccessGranted() {
        switch permissions.status {
        case .authorized, .limited:
            viewModel.onIntent(intent: HomeIntentGalleryPermissionGranted())
        default:
            break
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
    let galleryStatus: PHAuthorizationStatus

    let onOpenSettings: () -> Void
    let onOpenImage: (String) -> Void
    let onManagePermission: () -> Void
    let onIntent: (HomeIntent) -> Void

    private var isSearching: Bool {
        state.search.queryResults != nil
    }

    private var displayedDetections: [GalleryImageItem] {
        guard let queryResults = state.search.queryResults?.result else {
            return state.gallery.imagesState.detections
        }
        return queryResults.compactMap { uri in
            state.gallery.imagesState.detections.first {
                $0.uri == uri
            }
        }
    }

    private var displayedUncertainDetections: [GalleryImageItem] {
        guard let resultUris = state.search.queryResults?.result else {
            return state.gallery.imagesState.uncertainDetections
        }
        return resultUris.compactMap { uri in
            state.gallery.imagesState.uncertainDetections.first {
                $0.uri == uri
            }
        }
    }

    var body: some View {
        galleryGrid
            .homeToolbar(
                state: state,
                onOpenSettings: onOpenSettings,
                onIntent: onIntent
            )
            .homeScrollEdgeEffect()
    }

    private var galleryGrid: some View {
        ScrollView {
            VStack(spacing: 6) {
                if galleryStatus != .authorized && !isSearching {
                    GalleryPermissionCard(
                        isPartialAccess: galleryStatus == .limited,
                        onPrimaryAction: onManagePermission
                    )
                }

                if isSearching
                       && displayedDetections.isEmpty
                       && displayedUncertainDetections.isEmpty {
                    NoMatchesCard(query: state.search.queryResults?.value ?? "")
                }

                if !isSearching {
                    GridHeader(
                        status: state.gallery.imagesState.status,
                        onRescanRequested: {
                            onIntent(HomeIntentGalleryResetRequested())
                        }
                    )
                }

                LazyVGrid(
                    columns: [
                        GridItem(.flexible(), spacing: 6),
                        GridItem(.flexible(), spacing: 6),
                    ],
                    spacing: 6,
                    ) {
                    ForEach(displayedDetections, id: \.self) { item in
                        GalleryItem(item: item, onClick: onOpenImage)
                            .aspectRatio(1, contentMode: .fit)
                    }
                }

                //TODO: make those lazy
                ForEach(displayedUncertainDetections, id: \.self) { item in
                    UncertainGalleryItem(
                        item: item,
                        onClick: onOpenImage,
                        onAccept: { uri, score in
                            onIntent(
                                HomeIntentGallery(
                                    intent: GalleryIntentAcceptUncertain(
                                        uri: uri,
                                        score: score.score
                                    )
                                )
                            )
                        },
                        onDelete: { uri in
                            onIntent(
                                HomeIntentGallery(
                                    intent: GalleryIntentDeleteUncertain(
                                        uri: uri
                                    )
                                )
                            )
                        }
                    )
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.horizontal, 8)
        }
        .contentMargins(.top, 8, for: .scrollContent)
        .scrollIndicators(.hidden)
    }
}

private struct GridHeader: View {
    let status: GalleryStatusCounts

    let onRescanRequested: () -> Void

    @State var showResetDialog: Bool = false

    var body: some View {
        ScanStatusCard(statusCounts: status)
        .frame(maxWidth: .infinity)
        .onTapGesture {
            showResetDialog = true
        }
        .confirmationDialog(
            "Rescan gallery?",
            isPresented: $showResetDialog,
            ) {
            Button("Yes, rescan", action: onRescanRequested)
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
            galleryStatus: .authorized,
            onOpenSettings: {},
            onOpenImage: { _ in },
            onManagePermission: {},
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
            galleryStatus: .notDetermined,
            onOpenSettings: {},
            onOpenImage: { _ in },
            onManagePermission: {},
            onIntent: { _ in }
        )
    }
}

#Preview("Empty (partial access)") {
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
            galleryStatus: .limited,
            onOpenSettings: {},
            onOpenImage: { _ in },
            onManagePermission: {},
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
