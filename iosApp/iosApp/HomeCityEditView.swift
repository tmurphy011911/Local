import SwiftUI
import MapKit
import ComposeApp

// MARK: - MapKit-backed city search

/// Wraps MKLocalSearchCompleter for live city autocomplete (no Google key
/// needed — change_home_city's place_id is optional, so name + coords suffice).
final class CitySearchModel: NSObject, ObservableObject, MKLocalSearchCompleterDelegate {
    @Published var results: [MKLocalSearchCompletion] = []
    private let completer = MKLocalSearchCompleter()

    override init() {
        super.init()
        completer.delegate = self
        completer.resultTypes = .address
    }

    func update(_ query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        if trimmed.count < 2 { results = []; return }
        completer.queryFragment = trimmed
    }

    func completerDidUpdateResults(_ completer: MKLocalSearchCompleter) {
        results = completer.results
    }

    func completer(_ completer: MKLocalSearchCompleter, didFailWithError error: Error) {
        results = []
    }

    /// Resolve a completion to a display name + coordinates.
    func resolve(_ completion: MKLocalSearchCompletion, done: @escaping ((name: String, lat: Double, lng: Double)?) -> Void) {
        let request = MKLocalSearch.Request(completion: completion)
        MKLocalSearch(request: request).start { response, _ in
            guard let item = response?.mapItems.first, let loc = item.placemark.location else {
                done(nil); return
            }
            let name = item.placemark.locality ?? completion.title
            done((name: name, lat: loc.coordinate.latitude, lng: loc.coordinate.longitude))
        }
    }
}

// MARK: - Native home-city editor

struct HomeCityEditView: View {
    /// (name, lat, lng, completion(ok, rateLimited, nextAllowedAt))
    let onConfirm: (String, Double, Double, @escaping (Bool, Bool, String?) -> Void) -> Void
    let onDismiss: () -> Void

    @StateObject private var search = CitySearchModel()
    @State private var query = ""
    @State private var selected: (name: String, lat: Double, lng: Double)?
    @State private var message: String?
    @State private var saving = false

    var body: some View {
        NavigationView {
            Group {
                if let pick = selected {
                    confirmStep(pick)
                } else {
                    searchStep
                }
            }
            .navigationTitle("Change home city")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onDismiss() }
                }
            }
        }
    }

    private var searchStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            TextField("Search a city", text: $query)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .padding(16)
                .onChange(of: query) { search.update($0) }

            List(search.results, id: \.self) { result in
                Button {
                    search.resolve(result) { resolved in
                        if let r = resolved { selected = r } else { message = "Couldn't resolve that place — try another." }
                    }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(result.title).font(.headline)
                        if !result.subtitle.isEmpty {
                            Text(result.subtitle).font(.subheadline).foregroundColor(.secondary)
                        }
                    }
                }
            }
            .listStyle(.plain)

            if let message {
                Text(message).font(.footnote).foregroundColor(.red).padding(16)
            }
        }
    }

    private func confirmStep(_ pick: (name: String, lat: Double, lng: Double)) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Change your home city to \(pick.name)?")
                .font(.headline)
            Text("You won't be able to change it again for a year.")
                .font(.subheadline).foregroundColor(.secondary)
            if let message {
                Text(message).font(.subheadline).foregroundColor(.red)
            }
            Spacer()
            HStack {
                Button("Back") { message = nil; selected = nil }
                    .buttonStyle(.bordered)
                Spacer()
                Button {
                    saving = true; message = nil
                    onConfirm(pick.name, pick.lat, pick.lng) { ok, rateLimited, nextAllowedAt in
                        DispatchQueue.main.async {
                            saving = false
                            if ok {
                                onDismiss()
                            } else if rateLimited {
                                message = "You can't change your home city until \(nextAllowedAt ?? "later")."
                            } else {
                                message = "Couldn't change — try again."
                            }
                        }
                    }
                } label: {
                    if saving { ProgressView() } else { Text("Confirm").bold() }
                }
                .buttonStyle(.borderedProminent)
                .disabled(saving)
            }
        }
        .padding(20)
    }
}
