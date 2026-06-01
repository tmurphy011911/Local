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
            sheet.detents = [.large()]
            sheet.prefersGrabberVisible = true
        }
        // Wire dismiss-via-handle to onCancelled, but only if Done wasn't tapped.
        host.presentationController?.delegate = DismissCancelDelegate.shared
        DismissCancelDelegate.shared.attach(host: host, onCancel: onCancelled)
        top.present(host, animated: true)
    }
}

/// A single-calendar range picker: tap once to set the start, tap again to set
/// the end. The contiguous range between is highlighted. Tapping a day before
/// the current start moves the start; tapping once a full range is set restarts
/// the selection. No more "pick start, close, reopen for end".
private struct DateRangePickerView: View {
    let initialStart: Date
    let initialEnd: Date
    let onDone: (Date, Date) -> Void
    let onCancel: () -> Void

    @State private var start: Date?
    @State private var end: Date?
    @State private var visibleMonth: Date
    @Environment(\.dismiss) private var dismiss

    private let cal = Calendar.current

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
        let c = Calendar.current
        _start = State(initialValue: c.startOfDay(for: initialStart))
        // Only treat the incoming end as a real range endpoint when it differs
        // from start; otherwise begin in "pick the end" mode with just a start.
        let s = c.startOfDay(for: initialStart)
        let e = c.startOfDay(for: initialEnd)
        _end = State(initialValue: e > s ? e : nil)
        _visibleMonth = State(initialValue: s)
    }

    var body: some View {
        VStack(spacing: 0) {
            pickerHeader
            Divider()
            monthHeader
            weekdayHeader
            monthGrid
                .padding(.horizontal, 12)
                .padding(.top, 4)
            Divider().padding(.top, 12)
            summaryRows
            Spacer(minLength: 0)
        }
    }

    // MARK: - Header

    // Custom header instead of a NavigationStack toolbar: when this view is
    // hosted in a manually-presented UIHostingController pageSheet, the nav bar
    // fails to inset the content, so the month label collides with the Cancel
    // button. A plain header row keeps layout fully under our control.
    private var pickerHeader: some View {
        ZStack {
            Text("Select range")
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(.primary)
            HStack {
                Button("Cancel") {
                    DismissCancelDelegate.shared.markCommitted()
                    onCancel()
                    dismiss()
                }
                Spacer()
                Button("Done") {
                    DismissCancelDelegate.shared.markCommitted()
                    let s = start ?? cal.startOfDay(for: Date())
                    onDone(s, end ?? s)
                    dismiss()
                }
                .fontWeight(.bold)
                .disabled(start == nil)
            }
        }
        .tint(accent)
        .padding(.horizontal, 20)
        .padding(.top, 18)
        .padding(.bottom, 12)
    }

    private var monthHeader: some View {
        HStack {
            Text(monthTitleFormatter.string(from: visibleMonth))
                .font(.system(size: 22, weight: .bold))
            Spacer()
            Button { shiftMonth(-1) } label: {
                Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold))
            }
            .padding(.trailing, 18)
            Button { shiftMonth(1) } label: {
                Image(systemName: "chevron.right").font(.system(size: 18, weight: .semibold))
            }
        }
        .foregroundColor(.primary)
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 10)
    }

    private var weekdayHeader: some View {
        HStack(spacing: 0) {
            ForEach(orderedWeekdaySymbols, id: \.self) { sym in
                Text(sym.uppercased())
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity)
            }
        }
        .padding(.horizontal, 12)
    }

    // MARK: - Grid

    private var monthGrid: some View {
        let columns = Array(repeating: GridItem(.flexible(), spacing: 0), count: 7)
        return LazyVGrid(columns: columns, spacing: 6) {
            ForEach(Array(monthCells.enumerated()), id: \.offset) { _, day in
                if let day = day {
                    dayCell(day)
                } else {
                    Color.clear.frame(height: 40)
                }
            }
        }
    }

    @ViewBuilder
    private func dayCell(_ day: Date) -> some View {
        let isStart = start.map { cal.isDate(day, inSameDayAs: $0) } ?? false
        let isEnd = end.map { cal.isDate(day, inSameDayAs: $0) } ?? false
        let inRange = isInRange(day)
        let isEndpoint = isStart || isEnd

        Text("\(cal.component(.day, from: day))")
            .font(.system(size: 18, weight: isEndpoint ? .bold : .regular))
            .foregroundColor(isEndpoint ? .white : .primary)
            .frame(maxWidth: .infinity)
            .frame(height: 40)
            .background(
                ZStack {
                    if inRange && !isEndpoint {
                        Rectangle().fill(rangeFill)
                    }
                    if isEndpoint {
                        Circle().fill(accent).padding(2)
                    }
                }
            )
            .contentShape(Rectangle())
            .onTapGesture { select(day) }
    }

    // MARK: - Summary

    private var summaryRows: some View {
        VStack(spacing: 0) {
            summaryRow(label: "Start", value: start)
            Divider().padding(.leading, 20)
            summaryRow(label: "End", value: end)
        }
        .padding(.vertical, 4)
    }

    private func summaryRow(label: String, value: Date?) -> some View {
        HStack {
            Text(label).font(.system(size: 17, weight: .semibold)).foregroundColor(.primary)
            Spacer()
            Text(value.map { summaryFormatter.string(from: $0) } ?? "—")
                .font(.system(size: 17, weight: .semibold))
                .foregroundColor(value == nil ? .secondary : accent)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
    }

    // MARK: - Selection logic

    private func select(_ day: Date) {
        let d = cal.startOfDay(for: day)
        if let s = start, end == nil {
            if d < s {
                start = d          // tapped earlier than start → move start
            } else {
                end = d            // completes the range
            }
        } else {
            start = d              // fresh start (nothing set, or restart a full range)
            end = nil
        }
    }

    private func isInRange(_ day: Date) -> Bool {
        guard let s = start, let e = end else { return false }
        let d = cal.startOfDay(for: day)
        return d >= s && d <= e
    }

    private func shiftMonth(_ delta: Int) {
        if let m = cal.date(byAdding: .month, value: delta, to: visibleMonth) {
            visibleMonth = m
        }
    }

    // MARK: - Calendar math

    private var monthCells: [Date?] {
        let comps = cal.dateComponents([.year, .month], from: visibleMonth)
        guard let firstOfMonth = cal.date(from: comps),
              let range = cal.range(of: .day, in: .month, for: firstOfMonth)
        else { return [] }
        let firstWeekday = cal.component(.weekday, from: firstOfMonth)
        let leading = (firstWeekday - cal.firstWeekday + 7) % 7
        var cells: [Date?] = Array(repeating: nil, count: leading)
        for offset in 0..<range.count {
            cells.append(cal.date(byAdding: .day, value: offset, to: firstOfMonth))
        }
        return cells
    }

    private var orderedWeekdaySymbols: [String] {
        let symbols = cal.shortWeekdaySymbols
        let shift = cal.firstWeekday - 1
        return Array(symbols[shift...] + symbols[..<shift])
    }

    // MARK: - Styling / formatters

    // App coral (0xFF6B6B). Defined locally — the AppColor enums elsewhere are
    // file-private, so they can't be referenced from this file.
    private var accent: Color { Color(red: 1.0, green: 107.0 / 255.0, blue: 107.0 / 255.0) }
    private var rangeFill: Color { accent.opacity(0.18) }

    private var monthTitleFormatter: DateFormatter {
        let f = DateFormatter(); f.dateFormat = "MMMM yyyy"; return f
    }
    private var summaryFormatter: DateFormatter {
        let f = DateFormatter(); f.dateFormat = "MMM d, yyyy"; return f
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
