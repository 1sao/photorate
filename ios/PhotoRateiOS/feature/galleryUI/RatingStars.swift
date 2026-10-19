//
//  RatingStars.swift
//  PhotoRateiOS
//
//  Created by Ihor Pakharenko on 23. 9. 2026..
//

import SwiftUI
import shared

struct RatingStar: View {
    let fraction: CGFloat

    let filledColor: Color
    let emptyColor: Color
    let outlineWidth: CGFloat

    init(
        fraction: CGFloat,
        filledColor: Color = StarDefaults.filledColor,
        emptyColor: Color = StarDefaults.emptyColor,

        outlineWidth: CGFloat = StarDefaults.outlineWidth
    ) {
        self.fraction = fraction
        self.filledColor = filledColor
        self.emptyColor = emptyColor
        self.outlineWidth = outlineWidth
    }

    var body: some View {
        let fillFraction = fraction.coercedIn(to: 0...1)
        ZStack {
            Image(systemName: "star.fill")
                .resizable()
                .aspectRatio(1, contentMode: .fit)
                .foregroundStyle(emptyColor)
            ZStack {
                Image(systemName: "star.fill")
                    .resizable()
                    .aspectRatio(1, contentMode: .fit)
                    .foregroundStyle(filledColor)

                Image(systemName: "star.fill")
                    .resizable()
                    .blendMode(.destinationOut)
                    .aspectRatio(1, contentMode: .fit)
                    .padding(outlineWidth)
                    .mask(alignment: .trailing) {
                        Rectangle()
                            .containerRelativeFrame(.horizontal) { length, axis in
                                if axis == .horizontal {
                                    return length * (1 - fillFraction)
                                } else {
                                    return length
                                }
                            }

                    }

            }
            .compositingGroup()
        }

    }
}

struct RatingStarSelector<Filled: View, Empty: View>: View {
    let selected: Score
    let onSelect: (Score) -> Void

    let filledStar: () -> Filled
    let emptyStar: () -> Empty

    init(
        selected: Score,
        onSelected: @escaping (Score) -> Void,
        @ViewBuilder filledStar: @escaping () -> Filled = {
            @Environment(\.theme) var theme
            return RatingStar(
                fraction: 1,
                //TODO: this always retrieves default value, move accessing theme out of constructor.
                filledColor: theme.colorScheme.primary,
                outlineWidth: 0
            )
        },
        @ViewBuilder emptyStar: @escaping () -> Empty = {
            @Environment(\.theme) var theme
            return RatingStar(
                fraction: 0,
                filledColor: theme.colorScheme.outline,
                emptyColor: Color.clear,
                outlineWidth: 2
            )
        }
    ) {
        self.selected = selected
        self.onSelect = onSelected
        self.filledStar = filledStar
        self.emptyStar = emptyStar
    }

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Score.allCases, id: \.self) { score in
                ZStack {
                    if score.score <= selected.score {
                        filledStar()
                    } else {
                        emptyStar()
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture {
                    onSelect(score)
                }
            }
        }
    }
}

extension Score {
    var ratingStarFraction: CGFloat {
        switch self {
        case .one: 0
        case .two: 0.35
        case .three: 0.5
        case .four: 0.65
        case .five: 1
        }
    }
}

enum StarDefaults {
    static let filledColor: Color = .white
    static let emptyColor: Color = .white.opacity(0.1)

    static let outlineWidth: CGFloat = 1
}

#Preview {
    RatingStar(
        fraction: 0.6,
        filledColor: Color.green,
        emptyColor: Color.blue.opacity(0.1),
        outlineWidth: 2
    )
}

#Preview {
    VStack(spacing: 24) {
        RatingStarSelector(selected: Score.one, onSelected: { _ in }).frame(
            height: 32
        )
        RatingStarSelector(selected: Score.three, onSelected: { _ in })
        RatingStarSelector(selected: Score.five, onSelected: { _ in }).frame(
            width: 100
        )
    }
}
