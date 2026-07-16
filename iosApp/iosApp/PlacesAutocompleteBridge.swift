import MapKit
import ComposeApp

/// Wires Kotlin's PlacesAutocompleteBridge.query / .resolve to MKLocalSearchCompleter
/// + MKLocalSearch. Pure Apple Maps native — no API key required. Called once at
/// app start from ContentView.setupNativeBridges.
final class PlacesAutocompleteBridgeSetup: NSObject {
    static let shared = PlacesAutocompleteBridgeSetup()

    private let completer = MKLocalSearchCompleter()
    private var pendingResultCallback: ((String) -> Void)?

    private override init() {
        super.init()
        completer.resultTypes = [.address, .pointOfInterest]
        completer.delegate = self
    }

    static func register() {
        PlacesAutocompleteBridge.shared.query = { input, latBoxed, lngBoxed, onResultJson in
            DispatchQueue.main.async {
                shared.runQuery(
                    input: input as String,
                    lat: latBoxed?.doubleValue,
                    lng: lngBoxed?.doubleValue,
                    onResult: { result in _ = onResultJson(result) }
                )
            }
        }
        PlacesAutocompleteBridge.shared.resolve = { id, primary, secondary, onResultJson in
            DispatchQueue.main.async {
                shared.runResolve(
                    primary: primary as String,
                    secondary: secondary as String,
                    onResult: { result in _ = onResultJson(result) }
                )
            }
        }
    }

    private func runQuery(input: String, lat: Double?, lng: Double?, onResult: @escaping (String) -> Void) {
        if input.count < 3 { onResult("[]"); return }
        if let lat = lat, let lng = lng {
            completer.region = MKCoordinateRegion(
                center: CLLocationCoordinate2D(latitude: lat, longitude: lng),
                latitudinalMeters: 50_000, longitudinalMeters: 50_000
            )
        }
        pendingResultCallback = onResult
        completer.queryFragment = input
    }

    private func runResolve(primary: String, secondary: String, onResult: @escaping (String) -> Void) {
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = "\(primary) \(secondary)".trimmingCharacters(in: .whitespaces)
        if completer.region.span.latitudeDelta > 0 {
            request.region = completer.region
        }
        let search = MKLocalSearch(request: request)
        search.start { response, _ in
            guard let item = response?.mapItems.first else {
                onResult("")
                return
            }
            let placemark = item.placemark
            let formatted = formattedAddress(for: placemark, fallback: "\(primary), \(secondary)".trimmingCharacters(in: CharacterSet(charactersIn: ", ")))
            let coord = placemark.coordinate
            let json: [String: Any] = [
                "name": item.name ?? primary,
                "formattedAddress": formatted,
                "lat": coord.latitude,
                "lng": coord.longitude,
                // City/town (e.g. "Perkasie") for hometown display — `name` is the
                // street address on a precise pick.
                "locality": placemark.locality ?? "",
            ]
            onResult(jsonString(from: json))
        }
    }
}

extension PlacesAutocompleteBridgeSetup: MKLocalSearchCompleterDelegate {
    func completerDidUpdateResults(_ completer: MKLocalSearchCompleter) {
        let suggestions: [[String: Any]] = completer.results.prefix(3).map { result in
            [
                "id": "\(result.title)|\(result.subtitle)",
                "primaryText": result.title,
                "secondaryText": result.subtitle,
            ]
        }
        pendingResultCallback?(jsonString(from: suggestions))
        pendingResultCallback = nil
    }

    func completer(_ completer: MKLocalSearchCompleter, didFailWithError error: Error) {
        pendingResultCallback?("[]")
        pendingResultCallback = nil
    }
}

private func formattedAddress(for placemark: MKPlacemark, fallback: String) -> String {
    let lines = [
        placemark.thoroughfare,
        [placemark.locality, placemark.administrativeArea].compactMap { $0 }.joined(separator: ", "),
        placemark.postalCode,
    ].compactMap { $0 }.filter { !$0.isEmpty }
    if lines.isEmpty { return fallback }
    return lines.joined(separator: ", ")
}

private func jsonString(from object: Any) -> String {
    guard let data = try? JSONSerialization.data(withJSONObject: object) else { return "" }
    return String(data: data, encoding: .utf8) ?? ""
}
