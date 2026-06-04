import SwiftUI
import RevenueCat

// MARK: - Colors (match Compose PremiumPaywall)

private let paywallBg = Color(red: 0.102, green: 0.082, blue: 0.137) // #1A1523
private let coral = Color(red: 1.0, green: 0.42, blue: 0.42)         // #FF6B6B

// MARK: - Paywall View

struct PaywallView: View {
    /// Closes the paywall. `didPurchase` is true when the user successfully
    /// subscribed or restored an active entitlement, false when they tapped
    /// close without purchasing — callers use it to decide whether to resume
    /// a pending plan save or drop it.
    let onDismiss: (_ didPurchase: Bool) -> Void
    @State private var isAnnual = false
    @State private var monthlyPrice = "$6.99"
    @State private var annualPrice = "$49.99"
    @State private var introPrice: String? = "$0.99"
    @State private var isPurchasing = false

    // RevenueCat packages
    @State private var monthlyPackage: Package?
    @State private var annualPackage: Package?

    private var displayMonthly: String {
        introPrice ?? monthlyPrice
    }
    private var displaySubtext: String {
        if let intro = introPrice { return "then \(monthlyPrice)/mo" }
        return "/month"
    }
    private var annualPerMonth: String {
        let raw = Double(annualPrice.replacingOccurrences(of: "[^0-9.]", with: "", options: .regularExpression)) ?? 49.99
        let pm = raw / 12
        return String(format: "$%.2f/mo", pm)
    }

    var body: some View {
        ZStack {
            paywallBg.ignoresSafeArea()

            VStack(spacing: 0) {
                closeButton
                Spacer().frame(height: 16)
                wordmark
                Spacer().frame(height: 20)
                headline
                Spacer().frame(height: 28)
                comparisonMatrix
                Spacer()
                pricingToggle
                Spacer().frame(height: 16)
                subscribeCTA
                Spacer().frame(height: 12)
                secondaryCTAs
                Spacer().frame(height: 16)
            }
            .padding(.horizontal, 24)
            .padding(.top, 16)
            .padding(.bottom, 24)
        }
        .task { await loadPricing() }
    }

    // MARK: - Close

    private var closeButton: some View {
        HStack {
            Spacer()
            Button(action: { onDismiss(false) }) {
                Image(systemName: "xmark")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.white.opacity(0.5))
                    .frame(width: 32, height: 32)
            }
        }
    }

    // MARK: - Wordmark

    // Coral "l"s, white "oca", straight on the dark page.
    private var wordmark: some View {
        (Text("l").foregroundColor(coral)
            + Text("oca").foregroundColor(.white)
            + Text("l").foregroundColor(coral))
            .font(Font.custom("Fraunces", size: 44).weight(.black))
            .tracking(-1)
    }

    // MARK: - Headline

    private var headline: some View {
        VStack(spacing: 6) {
            Text("Plan together, effortlessly.")
                .font(Font.custom("Fraunces", size: 18).weight(.bold).italic())
                .foregroundColor(coral)
                .multilineTextAlignment(.center)
            BoTagline(fullText: "You and me.")
        }
    }

    // MARK: - Comparison Matrix

    private struct MatrixItem {
        let feature: String
        let free: String
        let pro: String
    }

    private let matrixRows = [
        MatrixItem(feature: "Saved Events", free: "5", pro: "Unlimited"),
        MatrixItem(feature: "Saved Places", free: "5", pro: "Unlimited"),
        MatrixItem(feature: "Plans Created", free: "1", pro: "Unlimited"),
    ]

    private var comparisonMatrix: some View {
        VStack(spacing: 0) {
            matrixRow(feature: "", free: "Free", pro: "Pro", isHeader: true)
            ForEach(matrixRows, id: \.feature) { row in
                Rectangle()
                    .fill(Color.white.opacity(0.06))
                    .frame(height: 1)
                matrixRow(feature: row.feature, free: row.free, pro: row.pro)
            }
        }
        .padding(.horizontal, 18)
        .background(Color.white.opacity(0.04))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private func matrixRow(feature: String, free: String, pro: String, isHeader: Bool = false) -> some View {
        HStack(spacing: 0) {
            Text(feature)
                .font(Font.custom("Nunito", size: 14).weight(.heavy))
                .foregroundColor(.white)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(free)
                .font(Font.custom("Nunito", size: isHeader ? 11 : 13).weight(isHeader ? .bold : .semibold))
                .foregroundColor(.white.opacity(isHeader ? 0.5 : 0.45))
                .frame(width: 72)
            Text(pro)
                .font(Font.custom("Nunito", size: isHeader ? 11 : 13).weight(isHeader ? .black : .heavy))
                .foregroundColor(coral)
                .frame(width: 88)
        }
        .padding(.vertical, 13)
    }

    // MARK: - Pricing Cards

    private var pricingToggle: some View {
        HStack(spacing: 10) {
            pricingCard(
                label: "Monthly",
                price: displayMonthly,
                subtext: displaySubtext,
                badge: introPrice != nil ? "First month" : nil,
                selected: !isAnnual
            ) { isAnnual = false }

            pricingCard(
                label: "Annual",
                price: annualPrice,
                subtext: annualPerMonth,
                badge: "Best value",
                selected: isAnnual
            ) { isAnnual = true }
        }
    }

    private func pricingCard(label: String, price: String, subtext: String, badge: String?, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                if let badge {
                    Text(badge)
                        .font(Font.custom("Nunito", size: 9).weight(.black))
                        .foregroundColor(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background(coral)
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                    Spacer().frame(height: 4)
                }
                Text(label)
                    .font(Font.custom("Nunito", size: 12).weight(.bold))
                    .foregroundColor(.white.opacity(0.6))
                Text(price)
                    .font(Font.custom("Fraunces", size: 24).weight(.black))
                    .foregroundColor(.white)
                Text(subtext)
                    .font(Font.custom("Nunito", size: 11).weight(.semibold))
                    .foregroundColor(.white.opacity(0.4))
            }
            .frame(maxWidth: .infinity)
            .frame(height: 140)
            .background(selected ? coral.opacity(0.1) : Color.white.opacity(0.04))
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(selected ? coral : Color.white.opacity(0.12), lineWidth: 2)
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Subscribe CTA

    private var subscribeCTA: some View {
        Button {
            Task { await purchase() }
        } label: {
            ZStack {
                if isPurchasing {
                    ProgressView()
                        .tint(.white)
                } else {
                    Text(ctaLabel)
                        .font(Font.custom("Nunito", size: 16).weight(.black))
                        .foregroundColor(.white)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(
                LinearGradient(colors: [coral, Color(red: 0.91, green: 0.36, blue: 0.36)],
                               startPoint: .leading, endPoint: .trailing)
            )
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .disabled(isPurchasing)
    }

    private var ctaLabel: String {
        if isAnnual { return "Start Local Pro" }
        if let intro = introPrice { return "Try for \(intro)" }
        return "Start Local Pro"
    }

    // MARK: - Not Now
    // Restore Purchase was removed — users who re-install or switch devices
    // get their entitlement back automatically via RevenueCat's
    // checkSubscriptionStatus on login / app launch.

    private var secondaryCTAs: some View {
        Button(action: { onDismiss(false) }) {
            Text("Not Now")
                .font(Font.custom("Nunito", size: 14).weight(.bold))
                .foregroundColor(.white.opacity(0.6))
        }
        .buttonStyle(.plain)
    }

    // MARK: - RevenueCat

    private func loadPricing() async {
        do {
            let offerings = try await Purchases.shared.offerings()
            guard let offering = offerings.current else { return }

            if let monthly = offering.monthly {
                monthlyPackage = monthly
                monthlyPrice = monthly.localizedPriceString
                // introductoryDiscount is nil if user already redeemed the intro
                // offer — Apple enforces one-per-Apple-ID. Clear the default so
                // returning users see $6.99, not $0.99.
                if let intro = monthly.storeProduct.introductoryDiscount {
                    introPrice = intro.localizedPriceString
                } else {
                    introPrice = nil
                }
            }
            if let annual = offering.annual {
                annualPackage = annual
                annualPrice = annual.localizedPriceString
            }
        } catch {
            print("PaywallView: Failed to load offerings: \(error)")
        }
    }

    private func purchase() async {
        isPurchasing = true
        defer { isPurchasing = false }

        let pkg = isAnnual ? annualPackage : monthlyPackage
        guard let pkg else {
            print("PaywallView: No package available")
            return
        }

        do {
            let result = try await Purchases.shared.purchase(package: pkg)
            if !result.userCancelled {
                print("PaywallView: Purchase successful")
                onDismiss(true)
            }
        } catch {
            print("PaywallView: Purchase error: \(error)")
        }
    }

}

// MARK: - Bo Tagline

/// Bo slides up from below, a coral cursor fades in and blinks, then the
/// tagline streams character-by-character — mirrors the splash/empty-state
/// BoTapLine on Android, retuned coral-on-dark for the paywall.
private struct BoTagline: View {
    let fullText: String

    @State private var revealed = 0
    @State private var boOffset: CGFloat = 24
    @State private var boOpacity: Double = 0
    @State private var cursorOpacity: Double = 0

    var body: some View {
        HStack(spacing: 8) {
            Image("BoTiny")
                .resizable()
                .scaledToFit()
                .frame(width: 26, height: 26)
                .offset(y: boOffset)
                .opacity(boOpacity)

            HStack(spacing: 1) {
                Text(String(fullText.prefix(revealed)))
                    .font(Font.custom("Fraunces", size: 16).weight(.bold).italic())
                    .foregroundColor(coral)
                Rectangle()
                    .fill(coral)
                    .frame(width: 2, height: 17)
                    .opacity(cursorOpacity)
            }
        }
        .task {
            // Let the paywall settle on screen before Bo makes an entrance.
            try? await Task.sleep(nanoseconds: 450_000_000)
            withAnimation(.spring(response: 0.5, dampingFraction: 0.72)) {
                boOffset = 0
                boOpacity = 1
            }
            withAnimation(.linear(duration: 0.35)) { cursorOpacity = 1 }
            try? await Task.sleep(nanoseconds: 720_000_000)
            guard !fullText.isEmpty else { return }
            for i in 1...fullText.count {
                revealed = i
                try? await Task.sleep(nanoseconds: 45_000_000)
            }
            try? await Task.sleep(nanoseconds: 350_000_000)
            withAnimation(.easeOut(duration: 0.26)) { cursorOpacity = 0 }
        }
    }
}
