import Photos
import UIKit
import XCTest

@testable import PhotoRateiOS

@MainActor
final class GalleryPermissionsModelTests: XCTestCase {

    func testRequestAccessFromNotDeterminedUpdatesStatus() async {
        let provider = FakePhotoAuthorizationProvider(
            currentStatus: .notDetermined,
            requestedStatus: .authorized
        )
        let model = makeModel(provider: provider)

        await model.requestAccess()

        XCTAssertEqual(model.status, .authorized)
        XCTAssertEqual(provider.requestCount, 1)
    }

    func testPrimaryActionRequestsAccessWhenNotDetermined() async {
        let provider = FakePhotoAuthorizationProvider(
            currentStatus: .notDetermined,
            requestedStatus: .authorized
        )
        let model = makeModel(provider: provider)

        await model.request()

        XCTAssertEqual(provider.requestCount, 1)
        XCTAssertEqual(model.status, .authorized)
    }

    func testPrimaryActionOpensSettingsWhenDenied() async {
        let provider = FakePhotoAuthorizationProvider(currentStatus: .denied)
        var settingsOpened = false
        let model = makeModel(
            provider: provider,
            openSettings: { settingsOpened = true }
        )

        await model.request()

        XCTAssertTrue(settingsOpened)
        XCTAssertEqual(provider.requestCount, 0)
    }

    func testPrimaryActionOpensSettingsWhenRestricted() async {
        let provider = FakePhotoAuthorizationProvider(currentStatus: .restricted)
        var settingsOpened = false
        let model = makeModel(
            provider: provider,
            openSettings: { settingsOpened = true }
        )

        await model.request()

        XCTAssertTrue(settingsOpened)
        XCTAssertEqual(provider.requestCount, 0)
    }

    func testPrimaryActionPresentsLimitedPickerWhenLimited() async {
        let provider = FakePhotoAuthorizationProvider(currentStatus: .limited)
        var pickerPresented = false
        let model = makeModel(
            provider: provider,
            presentLimitedPicker: { pickerPresented = true }
        )

        await model.request()

        XCTAssertTrue(pickerPresented)
        XCTAssertEqual(provider.requestCount, 0)
    }

    func testDidBecomeActiveRefreshesStatus() async {
        let notificationCenter = NotificationCenter()
        let provider = FakePhotoAuthorizationProvider(currentStatus: .notDetermined)
        let model = GalleryPermissionsModel(
            provider: provider,
            openSettings: {},
            presentLimitedPicker: { [] },
            notificationCenter: notificationCenter
        )

        provider.currentStatus = .authorized
        notificationCenter.post(
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
        await Task.yield()

        XCTAssertEqual(model.status, .authorized)
    }

    private func makeModel(
        provider: FakePhotoAuthorizationProvider,
        openSettings: @escaping () -> Void = {
        },
        presentLimitedPicker: @escaping @MainActor () async -> Void = {
        }
    ) -> GalleryPermissionsModel {
        GalleryPermissionsModel(
            provider: provider,
            openSettings: openSettings,
            presentLimitedPicker: {
                await presentLimitedPicker()
                return []
            },
            notificationCenter: NotificationCenter()
        )
    }
}

private final class FakePhotoAuthorizationProvider: PhotoAuthorizationProviding {
    var currentStatus: PHAuthorizationStatus
    let requestedStatus: PHAuthorizationStatus
    private(set) var requestCount = 0

    init(
        currentStatus: PHAuthorizationStatus,
        requestedStatus: PHAuthorizationStatus? = nil
    ) {
        self.currentStatus = currentStatus
        self.requestedStatus = requestedStatus ?? currentStatus
    }

    func requestAuthorization() async -> PHAuthorizationStatus {
        requestCount += 1
        currentStatus = requestedStatus
        return requestedStatus
    }
}
