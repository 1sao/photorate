//
//  ImageLoader.swift
//  PhotoRateiOS
//
//  Created by Ihor Pakharenko on 25. 9. 2026..
//

import Combine
import Photos
import SwiftUI

struct ImageUnavailablePlaceholder: View {
    @Environment(\.theme) private var theme

    var body: some View {
        Image(systemName: "photo.trianglebadge.exclamationmark")
            .font(.title3)
            .foregroundStyle(theme.colorScheme.onSurfaceVariant)
    }
}

protocol PHImageLoader {
    func load(uri: String, targetSize: CGSize) -> AsyncStream<ImageResult>
}

class DefaultPHImageLoader: PHImageLoader {
    let manager: PHImageManager = .default()

    func load(
        uri: String,
        targetSize: CGSize
    ) -> AsyncStream<ImageResult> {
        AsyncStream { continuation in
            let manager = PHImageManager.default()

            guard
                let asset = PHAsset.fetchAssets(
                    withLocalIdentifiers: [uri],
                    options: nil
                ).firstObject
            else {
                // The asset is gone or not visible with current permissions.
                continuation.yield(ImageResult(image: nil, rawInfo: nil))
                continuation.finish()
                return
            }

            let options = PHImageRequestOptions()
            options.resizeMode = .fast
            options.isNetworkAccessAllowed = true
            options.deliveryMode = .opportunistic

            let requestID = manager.requestImage(
                for: asset,
                targetSize: targetSize,
                contentMode: .aspectFill,
                options: options
            ) { image, info in
                let result = ImageResult(image: image, rawInfo: info)

                continuation.yield(result)

                if case true = result.info?.isDegraded {
                    continuation.finish()
                }
            }

            continuation.onTermination = { _ in
                manager.cancelImageRequest(requestID)
            }
        }
    }
}

class PreviewPHImageLoader: PHImageLoader {
    func load(uri: String, targetSize: CGSize) -> AsyncStream<ImageResult> {
        AsyncStream { continuation in
            Task { @MainActor in
                continuation.yield(
                    ImageResult(image: getPreviewImage(), rawInfo: nil)
                )
                continuation.finish()
            }
        }
    }

    @MainActor
    func getPreviewImage() -> UIImage? {
        let renderer = ImageRenderer(
            content: Image(systemName: "photo.fill").background(in: Rectangle())
                .backgroundStyle(.red)
        )
        renderer.scale = UIScreen.main.scale
        return renderer.uiImage
    }
}

struct UnimplementedPHImageLoader: PHImageLoader {
    func load(uri: String, targetSize: CGSize) -> AsyncStream<ImageResult> {
        assertionFailure("No PHImageLoader was injected into the environment.")
        return AsyncStream {
            $0.finish()
        }
    }
}

extension EnvironmentValues {
    @Entry var phImageLoader: PHImageLoader = UnimplementedPHImageLoader()
}

struct ImageResult {
    let image: UIImage?

    let info: Info?

    init(image: UIImage?, rawInfo: [AnyHashable: Any]?) {
        self.image = image
        self.info = rawInfo.map(Info.init)
    }

    struct Info {
        private let dictionary: [AnyHashable: Any]

        init(dictionary: [AnyHashable: Any]) {
            self.dictionary = dictionary
        }

        /// Returns true if PhotoKit returned a temporary low-quality preview thumbnail.
        var isDegraded: Bool {
            (dictionary[PHImageResultIsDegradedKey] as? Bool) ?? false
        }

        /// Returns true if the image request was cancelled before completion.
        var isCancelled: Bool {
            (dictionary[PHImageCancelledKey] as? Bool) ?? false
        }

        /// Returns true if the asset data is in iCloud and needs downloading.
        var isInCloud: Bool {
            (dictionary[PHImageResultIsInCloudKey] as? Bool) ?? false
        }

        /// Contains an error object if the image download/loading failed.
        var error: Error? {
            dictionary[PHImageErrorKey] as? Error
        }

        /// The unique numeric identifier assigned to this image loading request.
        var requestID: PHImageRequestID? {
            dictionary[PHImageResultRequestIDKey] as? PHImageRequestID
        }
    }
}
