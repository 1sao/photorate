//
//  HomeToolbar.swift
//  PhotoRateiOS
//
//  Created by Ihor Pakharenko on 7. 10. 2026..
//

import SwiftUI
import shared

struct HomeToolbar: ViewModifier {
    let state: HomeScreenUiState
    let onOpenSettings: () -> Void
    let onIntent: (HomeIntent) -> Void

    func body(content: Content) -> some View {
        content
        .toolbar {
            toolbarContent
        }
        .navigationBarHidden(true)
        .searchable(text: searchTextBinding, placement: .sidebar)
        .searchSuggestions {
            searchSuggestions
        }
        .onSubmit(of: .search) {
            onIntent(HomeIntentSearch(intent: SearchIntentSubmitSearch()))
        }
    }

    @ViewBuilder
    private var toolbarContent: some ToolbarContent {
        if #available(iOS 26.0, *) {
            DefaultToolbarItem(kind: .search, placement: .bottomBar)
            ToolbarSpacer(.flexible, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) {
                settingsButton
            }
        } else {
            ToolbarItem {
                settingsButton
            }
        }
    }

    private var settingsButton: some View {
        Button("Settings", systemImage: "gearshape") {
            onOpenSettings()
        }
    }

    @ViewBuilder
    private var searchSuggestions: some View {
        ForEach(
            state.search.recentSearches,
            id: \.self
        ) { item in
            Text(item).searchCompletion(item)
        }
        .listRowSeparator(.hidden)
        //TODO: show confidence slider
        //        Color.black.frame(width: 100, height: 300)
    }

    private var searchTextBinding: Binding<String> {
        Binding(
            get: { state.search.pendingQuery.value },
            set: { newValue in
                onIntent(
                    HomeIntentSearch(
                        intent: SearchIntentUpdateSearchQuery(query: newValue)
                    )
                )
            }
        )
    }
}

struct HomeScrollEdgeEffect: ViewModifier {
    func body(content: Content) -> some View {
        //TODO: implement custom gradient to support all OS versions
        content
    }
}

extension View {
    func homeToolbar(
        state: HomeScreenUiState,
        onOpenSettings: @escaping () -> Void,
        onIntent: @escaping (HomeIntent) -> Void,
        ) -> some View {
        modifier(
            HomeToolbar(
                state: state,
                onOpenSettings: onOpenSettings,
                onIntent: onIntent
            )
        )
    }

    func homeScrollEdgeEffect() -> some View {
        modifier(HomeScrollEdgeEffect())
    }
}

#Preview("Home toolbar") {
    PhotoRatePreview {
        Color.clear
            .modifier(
                HomeToolbar(
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
                        search: SearchUiState(
                            queryResults: nil,
                            pendingQuery: Query.companion.INITIAL,
                            recentSearches: [],
                            isInDevMode: false
                        )
                    ),
                    onOpenSettings: {},
                    onIntent: { _ in }
                )
            )
    }
}
