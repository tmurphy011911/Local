import SwiftUI
import RevenueCat

// MARK: - Colors (match Compose PremiumPaywall)

private let paywallBg = Color(red: 0.102, green: 0.082, blue: 0.137) // #1A1523
private let coral = Color(red: 1.0, green: 0.42, blue: 0.42)         // #FF6B6B

// MARK: - Paywall View

struct PaywallView: View {
    let onDismiss: () -> Void
    @State private var isAnnual = true
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
                appIcon
                Spacer().frame(height: 20)
                headline
                Spacer().frame(height: 28)
                benefits
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
            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.white.opacity(0.5))
                    .frame(width: 32, height: 32)
            }
        }
    }

    // MARK: - App Icon

    private var appIcon: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 14)
                .fill(coral)
                .frame(width: 56, height: 56)
            Text("L")
                .font(Font.custom("Fraunces", size: 28).weight(.black))
                .foregroundColor(.white)
        }
    }

    // MARK: - Headline

    private var headline: some View {
        VStack(spacing: 4) {
            Text("Yocal Premium")
                .font(Font.custom("Fraunces", size: 28).weight(.black))
                .foregroundColor(.white)
            Text("Plan together, effortlessly")
                .font(Font.custom("Fraunces", size: 16).weight(.bold).italic())
                .foregroundColor(coral)
        }
    }

    // MARK: - Benefits

    private var benefits: some View {
        VStack(spacing: 14) {
            benefitRow(icon: "calendar", title: "Unlimited Plans", sub: "Build as many shareable plans as you want")
            benefitRow(icon: "square.and.arrow.up", title: "Easily Share", sub: "One tap to send your plan to friends and family")
            benefitRow(icon: "bell.badge", title: "Weekly Snapshot", sub: "Personalized event picks delivered to you each week")
            benefitRow(icon: "person.3", title: "Group Coordination", sub: "Share, vote, and confirm plans with your crew")
        }
    }

    private func benefitRow(icon: String, title: String, sub: String) -> some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 10)
                    .fill(coral.opacity(0.15))
                    .frame(width: 36, height: 36)
                Image(systemName: icon)
                    .font(.system(size: 16))
                    .foregroundColor(coral)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(Font.custom("Nunito", size: 14).weight(.heavy))
                    .foregroundColor(.white)
                Text(sub)
                    .font(Font.custom("Nunito", size: 11).weight(.semibold))
                    .foregroundColor(.white.opacity(0.5))
            }
            Spacer()
        }
    }

    // MARK: - Pricing Cards

    private var pricingToggle: some View {
        HStack(spacing: 10) {
            pricingCard(
                label: "Annual",
                price: annualPrice,
                subtext: annualPerMonth,
                badge: "Best value",
                selected: isAnnual
            ) { isAnnual = true }

            pricingCard(
                label: "Monthly",
                price: displayMonthly,
                subtext: displaySubtext,
                badge: introPrice != nil ? "First month" : nil,
                selected: !isAnnual
            ) { isAnnual = false }
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
        if isAnnual { return "Start Premium" }
        if let intro = introPrice { return "Try for \(intro)" }
        return "Start Premium"
    }

    // MARK: - Remind Me Later + Restore

    private var secondaryCTAs: some View {
        VStack(spacing: 10) {
            Button(action: onDismiss) {
                Text("Remind me later")
                    .font(Font.custom("Nunito", size: 14).weight(.bold))
                    .foregroundColor(.white.opacity(0.6))
            }
            .buttonStyle(.plain)

            Button {
                Task { await restore() }
            } label: {
                Text("Restore purchase")
                    .font(Font.custom("Nunito", size: 12).weight(.bold))
                    .foregroundColor(.white.opacity(0.4))
                    .underline()
            }
            .buttonStyle(.plain)
        }
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
                onDismiss()
            }
        } catch {
            print("PaywallView: Purchase error: \(error)")
        }
    }

    private func restore() async {
        do {
            let info = try await Purchases.shared.restorePurchases()
            if info.entitlements.active.isEmpty == false {
                print("PaywallView: Restore successful")
                onDismiss()
            }
        } catch {
            print("PaywallView: Restore error: \(error)")
        }
    }
}
