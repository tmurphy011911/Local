import UIKit
import ComposeApp

/// Wires Kotlin's NativeTimePickerBridge.show to a UIDatePicker presented in
/// a page sheet from the topmost view controller. Called once at app start
/// from ContentView.setupNativeBridges.
enum NativeTimePickerBridgeSetup {
    static func register() {
        NativeTimePickerBridge.shared.show = { initialHour, initialMinute, onPicked, onCancelled in
            DispatchQueue.main.async {
                presentTimePicker(
                    initialHour: Int(truncating: initialHour),
                    initialMinute: Int(truncating: initialMinute),
                    onPicked: { h, m in
                        onPicked(KotlinInt(int: Int32(h)), KotlinInt(int: Int32(m)))
                    },
                    onCancelled: { onCancelled() }
                )
            }
        }
    }

    private static func presentTimePicker(
        initialHour: Int,
        initialMinute: Int,
        onPicked: @escaping (Int, Int) -> Void,
        onCancelled: @escaping () -> Void
    ) {
        guard let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene }).first,
              let window = scene.windows.first(where: { $0.isKeyWindow }),
              let root = window.rootViewController
        else { onCancelled(); return }

        var top = root
        while let presented = top.presentedViewController { top = presented }

        let vc = TimePickerViewController(
            initialHour: initialHour,
            initialMinute: initialMinute,
            onPicked: onPicked,
            onCancelled: onCancelled
        )
        vc.modalPresentationStyle = .pageSheet
        if let sheet = vc.sheetPresentationController {
            sheet.detents = [.medium()]
            sheet.prefersGrabberVisible = true
        }
        top.present(vc, animated: true)
    }
}

private final class TimePickerViewController: UIViewController {
    private let initialHour: Int
    private let initialMinute: Int
    private let onPicked: (Int, Int) -> Void
    private let onCancelled: () -> Void
    private let picker = UIDatePicker()
    private var didFinish = false

    init(
        initialHour: Int,
        initialMinute: Int,
        onPicked: @escaping (Int, Int) -> Void,
        onCancelled: @escaping () -> Void
    ) {
        self.initialHour = initialHour
        self.initialMinute = initialMinute
        self.onPicked = onPicked
        self.onCancelled = onCancelled
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground

        picker.datePickerMode = .time
        picker.preferredDatePickerStyle = .wheels
        var components = DateComponents()
        components.hour = initialHour
        components.minute = initialMinute
        if let date = Calendar.current.date(from: components) { picker.date = date }
        picker.translatesAutoresizingMaskIntoConstraints = false

        let title = UILabel()
        title.text = "Start Time"
        title.font = .systemFont(ofSize: 17, weight: .semibold)
        title.textColor = .label
        title.translatesAutoresizingMaskIntoConstraints = false

        let cancel = UIButton(type: .system)
        cancel.setTitle("Cancel", for: .normal)
        cancel.titleLabel?.font = .systemFont(ofSize: 17)
        cancel.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        cancel.translatesAutoresizingMaskIntoConstraints = false

        let done = UIButton(type: .system)
        done.setTitle("Done", for: .normal)
        done.titleLabel?.font = .systemFont(ofSize: 17, weight: .semibold)
        done.addTarget(self, action: #selector(doneTapped), for: .touchUpInside)
        done.translatesAutoresizingMaskIntoConstraints = false

        view.addSubview(cancel)
        view.addSubview(title)
        view.addSubview(done)
        view.addSubview(picker)

        NSLayoutConstraint.activate([
            cancel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            cancel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            done.topAnchor.constraint(equalTo: cancel.topAnchor),
            done.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            title.centerYAnchor.constraint(equalTo: cancel.centerYAnchor),
            title.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            picker.topAnchor.constraint(equalTo: cancel.bottomAnchor, constant: 8),
            picker.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            picker.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            picker.bottomAnchor.constraint(lessThanOrEqualTo: view.bottomAnchor),
        ])
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if !didFinish { onCancelled() }
    }

    @objc private func doneTapped() {
        didFinish = true
        let comps = Calendar.current.dateComponents([.hour, .minute], from: picker.date)
        let h = comps.hour ?? 0
        let m = comps.minute ?? 0
        dismiss(animated: true) { self.onPicked(h, m) }
    }

    @objc private func cancelTapped() {
        didFinish = true
        dismiss(animated: true) { self.onCancelled() }
    }
}
