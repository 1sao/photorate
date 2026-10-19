import Observation
import Photos
import PhotosUI
import UIKit

protocol PhotoAuthorizationProviding {
    var currentStatus: PHAuthorizationStatus { get }
    func requestAuthorization() async -> PHAuthorizationStatus
}

struct SystemPhotoAuthorizationProvider: PhotoAuthorizationProviding {
    var currentStatus: PHAuthorizationStatus {
        PHPhotoLibrary.authorizationStatus(for: .readWrite)
    }

    func requestAuthorization() async -> PHAuthorizationStatus {
        await PHPhotoLibrary.requestAuthorization(for: .readWrite)
    }
}

@MainActor
@Observable
final class GalleryPermissionsModel {
    private let provider: PhotoAuthorizationProviding
    private let openSettings: () -> Void
    private let presentLimitedPicker: @MainActor () async -> [String]
    private let notificationCenter: NotificationCenter
    private var appActiveObserver: NSObjectProtocol?

    private(set) var status: PHAuthorizationStatus

    init(
        provider: PhotoAuthorizationProviding =
        SystemPhotoAuthorizationProvider(),
        openSettings: @escaping () -> Void = SystemPhotoActions.openSettings,
        presentLimitedPicker: @escaping @MainActor () async -> [String] =
        SystemPhotoActions.presentLimitedLibraryPicker,
        notificationCenter: NotificationCenter = .default
    ) {
        self.provider = provider
        self.openSettings = openSettings
        self.presentLimitedPicker = presentLimitedPicker
        self.notificationCenter = notificationCenter
        status = provider.currentStatus
        appActiveObserver = notificationCenter.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.refresh()
            }
        }
    }

    isolated deinit {
        if let appActiveObserver {
            notificationCenter.removeObserver(appActiveObserver)
        }
    }

    private func refresh() {
        status = provider.currentStatus
    }

    func requestAccess() async {
        status = await provider.requestAuthorization()
    }

    /// Requests access while iOS still shows the system dialog, presents limited photo picker
    /// if necessary, opens app settings once it won't show the system dialog.
    func request() async {
        switch status {
        case .notDetermined:
            await requestAccess()
        case .limited:
            _ = await presentLimitedPicker()
        default:
            openSettings()
        }
    }
}

enum SystemPhotoActions {
    static func openSettings() {
        Task { @MainActor in
            guard
                let url = URL(
                    string: UIApplication.openSettingsURLString
                )
            else {
                return
            }
            await UIApplication.shared.open(url)
        }
    }

    @MainActor
    static func presentLimitedLibraryPicker() async -> [String] {
        guard let controller = UIApplication.topmostViewController else {
            return []
        }

        return await PHPhotoLibrary.shared().presentLimitedLibraryPicker(
            from: controller
        )
    }
}
