import SwiftUI
import UIKit
import ComposeApp

/// Wires Kotlin's NativeDateRangePickerBridge.show to a SwiftUI sheet
/// containing two native DatePickers (start + end). Called once at app start
/// from ContentView.setupNativeBridges.
enum NativeDateRangePickerBridgeSetup {
    static func register() {
        NativeDateRangePickerBridge.shared.show = { initialStartBoxed, initialEndBoxed, onPicked, onCancelled in
            DispatchQueue.main.async {
                presentRangePicker(
                    initialStart: initialStartBoxed?.int64Value,
                    initialEnd: initialEndBoxed?.int64Value,
                    onPicked: { s, e in
                        _ = onPicked(KotlinLong(value: s), KotlinLong(value: e))
                    },
                    onCancelled: { _ = onCancelled() }
                )
            }
        }
    }

    private static func presentRangePicker(
        initialStart: Int64?,
        initialEnd: Int64?,
        onPicked: @escaping (Int64, Int64) -> Void,
        onCancelled: @escaping () -> Void
    ) {
        guard let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene }).first,
              let window = scene.windows.first(where: { $0.isKeyWindow }),
              let root = window.rootViewController
        else { onCancelled(); return }

        var top = root
        while let presented = top.presentedViewController { top = presented }

        let startDate = initialStart.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000.0) } ?? Date()
        let endDate = initialEnd.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000.0) } ?? startDate

        let view = DateRangePickerView(
            initialStart: startDate,
            initialEnd: endDate,
            onDone: { s, e in
                onPicked(Int64(s.timeIntervalSince1970 * 1000), Int64(e.timeIntervalSince1970 * 1000))
            },
            onCancel: onCancelled
        )

        let host = UIHostingController(rootView: view)
        host.modalPresentationStyle = .pageSheet
        if let sheet = host.sheetPresentationController {
            sheet.detents = [.medium(), .large()]
            sheet.prefersGrabberVisible = true
        }
        // Wire dismiss-via-handle to onCancelled, but only if Done wasn't tapped.
        host.presentationController?.delegate = DismissCancelDelegate.shared
        DismissCancelDelegate.shared.attach(host: host, onCancel: onCancelled)
        top.present(host, animated: true)
    }
}

private struct DateRangePickerView: View {
    let initialStart: Date
    let initialEnd: Date
    let onDone: (Date, Date) -> Void
    let onCancel: () -> Void

    @State private var start: Date
    @State private var end: Date
    @Environment(\.dismiss) private var dismiss

    init(
        initialStart: Date,
        initialEnd: Date,
        onDone: @escaping (Date, Date) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.initialStart = initialStart
        self.initialEnd = initialEnd
        self.onDone = onDone
        self.onCancel = onCancel
        _start = State(initialValue: initialStart)
        _end = State(initialValue: initialEnd)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker("Start", selection: $start, displayedComponents: .date)
                    DatePicker("End", selection: $end, in: start..., displayedComponents: .date)
                } header: {
                    Text("Pick a date range")
                }
            }
            .navigationTitle("When is it?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        DismissCancelDelegate.shared.markCommitted()
                        onCancel()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        DismissCancelDelegate.shared.markCommitted()
                        onDone(start, end)
                        dismiss()
                    }
                    .fontWeight(.bold)
                }
            }
        }
    }
}

private final class DismissCancelDelegate: NSObject, UIAdaptivePresentationControllerDelegate {
    static let shared = DismissCancelDelegate()
    private weak var host: UIViewController?
    private var onCancel: (() -> Void)?
    private var committed = false

    func attach(host: UIViewController, onCancel: @escaping () -> Void) {
        self.host = host
        self.onCancel = onCancel
        self.committed = false
    }

    func markCommitted() { committed = true }

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        if !committed { onCancel?() }
        host = nil
        onCancel = nil
    }
}
