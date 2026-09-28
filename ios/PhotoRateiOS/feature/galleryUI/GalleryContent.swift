import Photos
import SwiftUI
import shared

struct NoMatchesCard: View {
    let query: String

    @Environment(\.theme) private var theme

    var body: some View {
        VStack {
            Text("No matches found for \(query)")
                .font(.title2)
                .padding(.bottom, 4)
            Text(
                "Try to word your search differently, or tweak the similarity threshold in Settings → Developer Mode."
            )
                .font(.body)
                .foregroundStyle(theme.colorScheme.onSurfaceVariant)
        }
        .padding(20)
        .background(theme.colorScheme.surfaceVariant)
        .clipShape(theme.shapes.largeIncreased)
        .cornerRadius(24)
    }
}

struct GalleryItem: View {
    let item: GalleryImageItem
    let onClick: (String) -> Void

    var body: some View {
        GalleryImage(uri: item.uri)
            //      .containerRelativeFrame(
            //        .horizontal,
            //        count: 5,
            //        spacing: 2,
            //      )
            //      .clipped()
            //      .frame(width: .infinity)
            //      .aspectRatio(1, contentMode: .fit)
            //      .frame(alignment: .topTrailing)
            //      .clipped()
            //      .aspectRatio(1, contentMode: .fit)
        .onTapGesture {
            onClick(item.uri)
        }

        .overlay(alignment: .topLeading) {
            RatingStar(fraction: item.score.ratingStarFraction)
                .frame(width: 20, height: 20, alignment: .topLeading)
                .padding(12)
        }
    }
}

struct UncertainGalleryItem: View {
    let item: GalleryImageItem
    let onClick: (String) -> Void
    let onAccept: (String, Score) -> Void
    let onDelete: (String) -> Void

    @Environment(\.theme) private var theme

    var body: some View {
        HStack {
            GalleryImage(uri: item.uri)
            .onTapGesture {
                onClick(item.uri)
            }
            .frame(maxWidth: .infinity)
            .aspectRatio(1, contentMode: .fit)

//        .aspectRatio(1, contentMode: .fill)
//        .frame(width: .infinity, height: .infinity)

//        .frame(maxWidth: .infinity)
//        .aspectRatio(1, contentMode: .fit)

            VStack {
//        Color.black
                @State var score: Score = item.score

                Text("Best guess")
                    .font(.subheadline)
                    .foregroundStyle(theme.colorScheme.onSurfaceVariant)
                RatingStarSelector(
                    selected: item.score,
                    onSelected: { new in score = new }
                )
                    .frame(maxWidth: 200)
                HStack(spacing: 8) {
                    Button(
                        "Accept score \(score.score)",
                        systemImage: "checkmark",
                        action: { onAccept(item.uri, score) }
                    )
                        .labelStyle(.iconOnly)
                    Button(
                        "Reject image",
                        systemImage: "xmark",
                        action: { onDelete(item.uri) }
                    )
                        .labelStyle(.iconOnly)
                        .foregroundStyle(theme.colorScheme.errorContainer)
                        .backgroundStyle(theme.colorScheme.onErrorContainer)
                }

//        Color.black

            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            //      .frame(height: .infinity)
            //      .frame(maxHeight: .infinity)

            .border(.red, width: 5)
//      .frame(maxHeight: .infinity)

//      .frame(width: .infinity, height: .infinity)
        }
        .background(theme.colorScheme.surfaceContainerLow)
        .border(.green)
        .clipShape(theme.shapes.largeIncreased)
    }
}

struct GalleryImage: View {
    @Environment(\.theme) private var theme
    @Environment(\.phImageLoader) private var imageLoader

    let uri: String
    let clipShape: AnyShape? = nil

    @State private var imageResult: ImageResult? = nil

    var body: some View {
        GeometryReader { geo in
            Group {
                switch (imageResult?.image, imageResult?.info?.error) {
                case (_, _?):
                    Image("todo")
                case (let image?, _):
                    Image(uiImage: image)
                        //            .resizable()
                        //            .aspectRatio(contentMode: .fill)
                        //            .aspectRatio(1, contentMode: .fit)
                        //            .clipped()
                        .resizable()
                        .scaledToFill()
                        .frame(width: geo.size.width, height: geo.size.height)
                default: Color.clear
                }
            }
            .clipShape(clipShape ?? theme.shapes.largeIncreased)
            .task(id: uri) {
                imageResult = nil

                let scale = UIScreen.main.scale
                let scaledSize = geo.size.applying(
                    .init(scaleX: scale, y: scale)
                )

                for await result in imageLoader.load(
                    uri: uri,
                    targetSize: scaledSize
                ) {
                    imageResult = result
                }
            }
        }
    }
}

#Preview {
    PhotoRatePreview {
        VStack(spacing: 8) {

            NoMatchesCard(query: "Preview Query")

            GalleryItem(
                item: GalleryImageItem(
                    uri: "uri",
                    score: Score.four,
                    scannedAt: 0
                ),
                onClick: { _ in }
            ).frame(width: 120, height: 120).border(.red)

            UncertainGalleryItem(
                item: GalleryImageItem(
                    uri: "uri",
                    score: Score.four,
                    scannedAt: 0
                ),
                onClick: { _ in },
                onAccept: { _, _ in },
                onDelete: { _ in }
            )

            let images = [
                "photo", "photo.fill", "photo.circle", "photo.artframe",
            ]
            LazyVGrid(
                columns: [GridItem(.flexible()), GridItem(.flexible())],
                spacing: 2
            ) {
                ForEach(images, id: \.self) { image in
                    Image(systemName: image)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .aspectRatio(1, contentMode: .fit)
                        .containerRelativeFrame(.horizontal, count: 2, spacing: 2)
                        .clipped()
                }
            }
        }
    }
}
