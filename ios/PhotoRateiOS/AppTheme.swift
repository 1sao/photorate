//
//  AppTheme.swift
//  PhotoRateiOS
//
//  Created by Ihor Pakharenko on 22. 9. 2026..
//

import SwiftUI

struct ThemeColorScheme {
    let primary: Color
    let onPrimary: Color
    let primaryContainer: Color
    let onPrimaryContainer: Color
    let secondary: Color
    let onSecondary: Color
    let secondaryContainer: Color
    let onSecondaryContainer: Color
    let tertiary: Color
    let onTertiary: Color
    let tertiaryContainer: Color
    let onTertiaryContainer: Color
    let error: Color
    let onError: Color
    let errorContainer: Color
    let onErrorContainer: Color
    let background: Color
    let onBackground: Color
    let surface: Color
    let onSurface: Color
    let surfaceVariant: Color
    let onSurfaceVariant: Color
    let outline: Color
    let outlineVariant: Color
    let scrim: Color
    let inverseSurface: Color
    let inverseOnSurface: Color
    let inversePrimary: Color
    let surfaceDim: Color
    let surfaceBright: Color
    let surfaceContainerLowest: Color
    let surfaceContainerLow: Color
    let surfaceContainer: Color
    let surfaceContainerHigh: Color
    let surfaceContainerHighest: Color
}

extension ThemeColorScheme {
    static let light = ThemeColorScheme(
        primary: ThemeColors.primaryLight,
        onPrimary: ThemeColors.onPrimaryLight,
        primaryContainer: ThemeColors.primaryContainerLight,
        onPrimaryContainer: ThemeColors.onPrimaryContainerLight,
        secondary: ThemeColors.secondaryLight,
        onSecondary: ThemeColors.onSecondaryLight,
        secondaryContainer: ThemeColors.secondaryContainerLight,
        onSecondaryContainer: ThemeColors.onSecondaryContainerLight,
        tertiary: ThemeColors.tertiaryLight,
        onTertiary: ThemeColors.onTertiaryLight,
        tertiaryContainer: ThemeColors.tertiaryContainerLight,
        onTertiaryContainer: ThemeColors.onTertiaryContainerLight,
        error: ThemeColors.errorLight,
        onError: ThemeColors.onErrorLight,
        errorContainer: ThemeColors.errorContainerLight,
        onErrorContainer: ThemeColors.onErrorContainerLight,
        background: ThemeColors.backgroundLight,
        onBackground: ThemeColors.onBackgroundLight,
        surface: ThemeColors.surfaceLight,
        onSurface: ThemeColors.onSurfaceLight,
        surfaceVariant: ThemeColors.surfaceVariantLight,
        onSurfaceVariant: ThemeColors.onSurfaceVariantLight,
        outline: ThemeColors.outlineLight,
        outlineVariant: ThemeColors.outlineVariantLight,
        scrim: ThemeColors.scrimLight,
        inverseSurface: ThemeColors.inverseSurfaceLight,
        inverseOnSurface: ThemeColors.inverseOnSurfaceLight,
        inversePrimary: ThemeColors.inversePrimaryLight,
        surfaceDim: ThemeColors.surfaceDimLight,
        surfaceBright: ThemeColors.surfaceBrightLight,
        surfaceContainerLowest: ThemeColors.surfaceContainerLowestLight,
        surfaceContainerLow: ThemeColors.surfaceContainerLowLight,
        surfaceContainer: ThemeColors.surfaceContainerLight,
        surfaceContainerHigh: ThemeColors.surfaceContainerHighLight,
        surfaceContainerHighest: ThemeColors.surfaceContainerHighestLight
    )

    static let lightMediumContrast = ThemeColorScheme(
        primary: ThemeColors.primaryLightMediumContrast,
        onPrimary: ThemeColors.onPrimaryLightMediumContrast,
        primaryContainer: ThemeColors.primaryContainerLightMediumContrast,
        onPrimaryContainer: ThemeColors.onPrimaryContainerLightMediumContrast,
        secondary: ThemeColors.secondaryLightMediumContrast,
        onSecondary: ThemeColors.onSecondaryLightMediumContrast,
        secondaryContainer: ThemeColors.secondaryContainerLightMediumContrast,
        onSecondaryContainer: ThemeColors.onSecondaryContainerLightMediumContrast,
        tertiary: ThemeColors.tertiaryLightMediumContrast,
        onTertiary: ThemeColors.onTertiaryLightMediumContrast,
        tertiaryContainer: ThemeColors.tertiaryContainerLightMediumContrast,
        onTertiaryContainer: ThemeColors.onTertiaryContainerLightMediumContrast,
        error: ThemeColors.errorLightMediumContrast,
        onError: ThemeColors.onErrorLightMediumContrast,
        errorContainer: ThemeColors.errorContainerLightMediumContrast,
        onErrorContainer: ThemeColors.onErrorContainerLightMediumContrast,
        background: ThemeColors.backgroundLightMediumContrast,
        onBackground: ThemeColors.onBackgroundLightMediumContrast,
        surface: ThemeColors.surfaceLightMediumContrast,
        onSurface: ThemeColors.onSurfaceLightMediumContrast,
        surfaceVariant: ThemeColors.surfaceVariantLightMediumContrast,
        onSurfaceVariant: ThemeColors.onSurfaceVariantLightMediumContrast,
        outline: ThemeColors.outlineLightMediumContrast,
        outlineVariant: ThemeColors.outlineVariantLightMediumContrast,
        scrim: ThemeColors.scrimLightMediumContrast,
        inverseSurface: ThemeColors.inverseSurfaceLightMediumContrast,
        inverseOnSurface: ThemeColors.inverseOnSurfaceLightMediumContrast,
        inversePrimary: ThemeColors.inversePrimaryLightMediumContrast,
        surfaceDim: ThemeColors.surfaceDimLightMediumContrast,
        surfaceBright: ThemeColors.surfaceBrightLightMediumContrast,
        surfaceContainerLowest: ThemeColors
            .surfaceContainerLowestLightMediumContrast,
        surfaceContainerLow: ThemeColors.surfaceContainerLowLightMediumContrast,
        surfaceContainer: ThemeColors.surfaceContainerLightMediumContrast,
        surfaceContainerHigh: ThemeColors.surfaceContainerHighLightMediumContrast,
        surfaceContainerHighest: ThemeColors
            .surfaceContainerHighestLightMediumContrast
    )

    static let lightHighContrast = ThemeColorScheme(
        primary: ThemeColors.primaryLightHighContrast,
        onPrimary: ThemeColors.onPrimaryLightHighContrast,
        primaryContainer: ThemeColors.primaryContainerLightHighContrast,
        onPrimaryContainer: ThemeColors.onPrimaryContainerLightHighContrast,
        secondary: ThemeColors.secondaryLightHighContrast,
        onSecondary: ThemeColors.onSecondaryLightHighContrast,
        secondaryContainer: ThemeColors.secondaryContainerLightHighContrast,
        onSecondaryContainer: ThemeColors.onSecondaryContainerLightHighContrast,
        tertiary: ThemeColors.tertiaryLightHighContrast,
        onTertiary: ThemeColors.onTertiaryLightHighContrast,
        tertiaryContainer: ThemeColors.tertiaryContainerLightHighContrast,
        onTertiaryContainer: ThemeColors.onTertiaryContainerLightHighContrast,
        error: ThemeColors.errorLightHighContrast,
        onError: ThemeColors.onErrorLightHighContrast,
        errorContainer: ThemeColors.errorContainerLightHighContrast,
        onErrorContainer: ThemeColors.onErrorContainerLightHighContrast,
        background: ThemeColors.backgroundLightHighContrast,
        onBackground: ThemeColors.onBackgroundLightHighContrast,
        surface: ThemeColors.surfaceLightHighContrast,
        onSurface: ThemeColors.onSurfaceLightHighContrast,
        surfaceVariant: ThemeColors.surfaceVariantLightHighContrast,
        onSurfaceVariant: ThemeColors.onSurfaceVariantLightHighContrast,
        outline: ThemeColors.outlineLightHighContrast,
        outlineVariant: ThemeColors.outlineVariantLightHighContrast,
        scrim: ThemeColors.scrimLightHighContrast,
        inverseSurface: ThemeColors.inverseSurfaceLightHighContrast,
        inverseOnSurface: ThemeColors.inverseOnSurfaceLightHighContrast,
        inversePrimary: ThemeColors.inversePrimaryLightHighContrast,
        surfaceDim: ThemeColors.surfaceDimLightHighContrast,
        surfaceBright: ThemeColors.surfaceBrightLightHighContrast,
        surfaceContainerLowest: ThemeColors.surfaceContainerLowestLightHighContrast,
        surfaceContainerLow: ThemeColors.surfaceContainerLowLightHighContrast,
        surfaceContainer: ThemeColors.surfaceContainerLightHighContrast,
        surfaceContainerHigh: ThemeColors.surfaceContainerHighLightHighContrast,
        surfaceContainerHighest: ThemeColors
            .surfaceContainerHighestLightHighContrast
    )

    static let dark = ThemeColorScheme(
        primary: ThemeColors.primaryDark,
        onPrimary: ThemeColors.onPrimaryDark,
        primaryContainer: ThemeColors.primaryContainerDark,
        onPrimaryContainer: ThemeColors.onPrimaryContainerDark,
        secondary: ThemeColors.secondaryDark,
        onSecondary: ThemeColors.onSecondaryDark,
        secondaryContainer: ThemeColors.secondaryContainerDark,
        onSecondaryContainer: ThemeColors.onSecondaryContainerDark,
        tertiary: ThemeColors.tertiaryDark,
        onTertiary: ThemeColors.onTertiaryDark,
        tertiaryContainer: ThemeColors.tertiaryContainerDark,
        onTertiaryContainer: ThemeColors.onTertiaryContainerDark,
        error: ThemeColors.errorDark,
        onError: ThemeColors.onErrorDark,
        errorContainer: ThemeColors.errorContainerDark,
        onErrorContainer: ThemeColors.onErrorContainerDark,
        background: ThemeColors.backgroundDark,
        onBackground: ThemeColors.onBackgroundDark,
        surface: ThemeColors.surfaceDark,
        onSurface: ThemeColors.onSurfaceDark,
        surfaceVariant: ThemeColors.surfaceVariantDark,
        onSurfaceVariant: ThemeColors.onSurfaceVariantDark,
        outline: ThemeColors.outlineDark,
        outlineVariant: ThemeColors.outlineVariantDark,
        scrim: ThemeColors.scrimDark,
        inverseSurface: ThemeColors.inverseSurfaceDark,
        inverseOnSurface: ThemeColors.inverseOnSurfaceDark,
        inversePrimary: ThemeColors.inversePrimaryDark,
        surfaceDim: ThemeColors.surfaceDimDark,
        surfaceBright: ThemeColors.surfaceBrightDark,
        surfaceContainerLowest: ThemeColors.surfaceContainerLowestDark,
        surfaceContainerLow: ThemeColors.surfaceContainerLowDark,
        surfaceContainer: ThemeColors.surfaceContainerDark,
        surfaceContainerHigh: ThemeColors.surfaceContainerHighDark,
        surfaceContainerHighest: ThemeColors.surfaceContainerHighestDark
    )

    static let darkMediumContrast = ThemeColorScheme(
        primary: ThemeColors.primaryDarkMediumContrast,
        onPrimary: ThemeColors.onPrimaryDarkMediumContrast,
        primaryContainer: ThemeColors.primaryContainerDarkMediumContrast,
        onPrimaryContainer: ThemeColors.onPrimaryContainerDarkMediumContrast,
        secondary: ThemeColors.secondaryDarkMediumContrast,
        onSecondary: ThemeColors.onSecondaryDarkMediumContrast,
        secondaryContainer: ThemeColors.secondaryContainerDarkMediumContrast,
        onSecondaryContainer: ThemeColors.onSecondaryContainerDarkMediumContrast,
        tertiary: ThemeColors.tertiaryDarkMediumContrast,
        onTertiary: ThemeColors.onTertiaryDarkMediumContrast,
        tertiaryContainer: ThemeColors.tertiaryContainerDarkMediumContrast,
        onTertiaryContainer: ThemeColors.onTertiaryContainerDarkMediumContrast,
        error: ThemeColors.errorDarkMediumContrast,
        onError: ThemeColors.onErrorDarkMediumContrast,
        errorContainer: ThemeColors.errorContainerDarkMediumContrast,
        onErrorContainer: ThemeColors.onErrorContainerDarkMediumContrast,
        background: ThemeColors.backgroundDarkMediumContrast,
        onBackground: ThemeColors.onBackgroundDarkMediumContrast,
        surface: ThemeColors.surfaceDarkMediumContrast,
        onSurface: ThemeColors.onSurfaceDarkMediumContrast,
        surfaceVariant: ThemeColors.surfaceVariantDarkMediumContrast,
        onSurfaceVariant: ThemeColors.onSurfaceVariantDarkMediumContrast,
        outline: ThemeColors.outlineDarkMediumContrast,
        outlineVariant: ThemeColors.outlineVariantDarkMediumContrast,
        scrim: ThemeColors.scrimDarkMediumContrast,
        inverseSurface: ThemeColors.inverseSurfaceDarkMediumContrast,
        inverseOnSurface: ThemeColors.inverseOnSurfaceDarkMediumContrast,
        inversePrimary: ThemeColors.inversePrimaryDarkMediumContrast,
        surfaceDim: ThemeColors.surfaceDimDarkMediumContrast,
        surfaceBright: ThemeColors.surfaceBrightDarkMediumContrast,
        surfaceContainerLowest: ThemeColors
            .surfaceContainerLowestDarkMediumContrast,
        surfaceContainerLow: ThemeColors.surfaceContainerLowDarkMediumContrast,
        surfaceContainer: ThemeColors.surfaceContainerDarkMediumContrast,
        surfaceContainerHigh: ThemeColors.surfaceContainerHighDarkMediumContrast,
        surfaceContainerHighest: ThemeColors
            .surfaceContainerHighestDarkMediumContrast
    )

    static let darkHighContrast = ThemeColorScheme(
        primary: ThemeColors.primaryDarkHighContrast,
        onPrimary: ThemeColors.onPrimaryDarkHighContrast,
        primaryContainer: ThemeColors.primaryContainerDarkHighContrast,
        onPrimaryContainer: ThemeColors.onPrimaryContainerDarkHighContrast,
        secondary: ThemeColors.secondaryDarkHighContrast,
        onSecondary: ThemeColors.onSecondaryDarkHighContrast,
        secondaryContainer: ThemeColors.secondaryContainerDarkHighContrast,
        onSecondaryContainer: ThemeColors.onSecondaryContainerDarkHighContrast,
        tertiary: ThemeColors.tertiaryDarkHighContrast,
        onTertiary: ThemeColors.onTertiaryDarkHighContrast,
        tertiaryContainer: ThemeColors.tertiaryContainerDarkHighContrast,
        onTertiaryContainer: ThemeColors.onTertiaryContainerDarkHighContrast,
        error: ThemeColors.errorDarkHighContrast,
        onError: ThemeColors.onErrorDarkHighContrast,
        errorContainer: ThemeColors.errorContainerDarkHighContrast,
        onErrorContainer: ThemeColors.onErrorContainerDarkHighContrast,
        background: ThemeColors.backgroundDarkHighContrast,
        onBackground: ThemeColors.onBackgroundDarkHighContrast,
        surface: ThemeColors.surfaceDarkHighContrast,
        onSurface: ThemeColors.onSurfaceDarkHighContrast,
        surfaceVariant: ThemeColors.surfaceVariantDarkHighContrast,
        onSurfaceVariant: ThemeColors.onSurfaceVariantDarkHighContrast,
        outline: ThemeColors.outlineDarkHighContrast,
        outlineVariant: ThemeColors.outlineVariantDarkHighContrast,
        scrim: ThemeColors.scrimDarkHighContrast,
        inverseSurface: ThemeColors.inverseSurfaceDarkHighContrast,
        inverseOnSurface: ThemeColors.inverseOnSurfaceDarkHighContrast,
        inversePrimary: ThemeColors.inversePrimaryDarkHighContrast,
        surfaceDim: ThemeColors.surfaceDimDarkHighContrast,
        surfaceBright: ThemeColors.surfaceBrightDarkHighContrast,
        surfaceContainerLowest: ThemeColors.surfaceContainerLowestDarkHighContrast,
        surfaceContainerLow: ThemeColors.surfaceContainerLowDarkHighContrast,
        surfaceContainer: ThemeColors.surfaceContainerDarkHighContrast,
        surfaceContainerHigh: ThemeColors.surfaceContainerHighDarkHighContrast,
        surfaceContainerHighest: ThemeColors.surfaceContainerHighestDarkHighContrast
    )
}

struct Theme {
    var colorScheme: ThemeColorScheme = .light
    var shapes = Shapes()

    struct Shapes {
        let extraSmall = AnyShape(RoundedRectangle(cornerRadius: 4))
        let small = AnyShape(RoundedRectangle(cornerRadius: 8))
        let medium = AnyShape(RoundedRectangle(cornerRadius: 12))
        let large = AnyShape(RoundedRectangle(cornerRadius: 16))
        let largeIncreased = AnyShape(RoundedRectangle(cornerRadius: 20))
        let extraLarge = AnyShape(RoundedRectangle(cornerRadius: 28))
        let extraLargeIncreased = AnyShape(RoundedRectangle(cornerRadius: 32))
        let extraExtraLarge = AnyShape(RoundedRectangle(cornerRadius: 48))
    }
}

extension EnvironmentValues {
    @Entry var theme = Theme()
}

struct PhotoRateTheme<Content: View>: View {
    @Environment(\.colorScheme) private var systemColorScheme
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    let darkTheme: Bool?
    let content: () -> Content

    init(darkTheme: Bool? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.darkTheme = darkTheme
        self.content = content
    }

    private var resolvedColorScheme: ThemeColorScheme {
        let dark = darkTheme ?? (systemColorScheme == .dark)
        switch (dark, colorSchemeContrast) {
        case (false, .increased): return .lightHighContrast
        case (true, .increased): return .darkHighContrast
        case (false, _): return .light
        case (true, _): return .dark
        }
    }

    var body: some View {
        let dark = darkTheme ?? (systemColorScheme == .dark)
        content()
            .environment(\.theme, Theme(colorScheme: resolvedColorScheme))
            .preferredColorScheme(dark ? .dark : .light)
    }
}

// usage
//@Environment(\.theme) private var theme
//PhotoRateTheme { ... }
