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

    //  let viewModel: HomeViewModel = koin.get(
    //    qualifier: shared.getOriginalKotlinClass(objCClass: HomeViewModel)
    //  ) as! HomeViewModel

    let viewModel: HomeViewModel = inject()

    var body: some View {
        Observing(viewModel.uiState) { state in
            HomeScreenContent(
                state: state,
                onOpenSettings: onOpenSettings,
                onOpenImage: onOpenImage,
                onIntent: viewModel.onIntent
            ).task {
                viewModel.onIntent(intent: HomeIntentGalleryPermissionGranted())
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

    var body: some View {
        ScrollView {
            LazyVGrid(
                columns: [GridItem(.flexible()), GridItem(.flexible())],
                spacing: 2
            ) {
                ScanStatusCard(statusCounts: state.gallery.imagesState.status)
                    .frame(width: .infinity)

                ForEach(state.gallery.imagesState.detections, id: \.self) { item in
                    GalleryItem(item: item, onClick: onOpenImage)
                        .frame(height: 200)
                }
            }
            .frame(width: .infinity, height: .infinity)
        }
    }
}

#Preview {
    PhotoRatePreview {
        HomeScreenContent(
            state: HomeScreenUiState(
                gallery: GalleryUiState(
                    imagesState: GalleryUiState.ImagesState(
                        status: GalleryStatusCounts(
                            pending: 15,
                            done: 20,
                            failed: 0,
                            detected: 20
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
                search: SearchUiState(
                    queryResults: nil,
                    pendingQuery: Query.companion.INITIAL,
                    recentSearches: [],
                    isInDevMode: false
                )
            ),
            onOpenSettings: {
            },
            onOpenImage: { _ in },
            onIntent: { _ in }
        )
    }
}
