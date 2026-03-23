import UIKit
import ComposeApp

/// Native UIKit tab bar that overlays the Compose app.
/// Communicates with Compose via TabBarBridge.
class NativeTabBar {
    static let shared = NativeTabBar()

    private var tabBarView: UIView?
    private var buttons: [UIButton] = []
    private var aiFab: UIButton?
    private var selectedIndex: Int = 0
    private var userInitial: String = ""

    private let tabs: [(icon: String, iconFilled: String, label: String)] = [
        ("house", "house.fill", "Home"),
        ("mappin.and.ellipse", "mappin.circle.fill", "Places"),
        ("calendar", "calendar.circle.fill", "Plans"),
        ("person", "person.fill", "Profile"),
    ]

    private let coralColor = UIColor(red: 1.0, green: 0.42, blue: 0.42, alpha: 1.0)
    private let inkColor = UIColor(red: 0.55, green: 0.50, blue: 0.63, alpha: 1.0)

    func setup(in window: UIWindow) {
        let safeBottom = window.safeAreaInsets.bottom
        let barHeight: CGFloat = 52 + safeBottom
        let barY = window.bounds.height - barHeight

        // Container
        let bar = UIView(frame: CGRect(x: 0, y: barY, width: window.bounds.width, height: barHeight))
        bar.backgroundColor = UIColor(red: 0.94, green: 0.93, blue: 0.96, alpha: 0.92)

        // Top border
        let border = UIView(frame: CGRect(x: 0, y: 0, width: bar.bounds.width, height: 0.5))
        border.backgroundColor = UIColor(red: 0.88, green: 0.86, blue: 0.91, alpha: 0.6)
        bar.addSubview(border)

        // Tab buttons — left portion (leave room for AI FAB on right)
        let tabAreaWidth = window.bounds.width - 72 // room for FAB
        let tabWidth = tabAreaWidth / CGFloat(tabs.count)

        buttons = []
        for (index, tab) in tabs.enumerated() {
            let btn = createTabButton(tab: tab, index: index, frame: CGRect(
                x: CGFloat(index) * tabWidth,
                y: 4,
                width: tabWidth,
                height: 48
            ))
            bar.addSubview(btn)
            buttons.append(btn)
        }

        // AI FAB — coral circle on the right
        let fabSize: CGFloat = 52
        let fab = UIButton(frame: CGRect(
            x: window.bounds.width - fabSize - 16,
            y: 0,
            width: fabSize,
            height: fabSize
        ))
        fab.backgroundColor = coralColor
        fab.layer.cornerRadius = fabSize / 2
        fab.layer.shadowColor = coralColor.cgColor
        fab.layer.shadowOffset = CGSize(width: 0, height: 4)
        fab.layer.shadowRadius = 12
        fab.layer.shadowOpacity = 0.35

        let starConfig = UIImage.SymbolConfiguration(pointSize: 22, weight: .medium)
        fab.setImage(UIImage(systemName: "sparkles", withConfiguration: starConfig), for: .normal)
        fab.tintColor = .white
        fab.addTarget(self, action: #selector(chatTapped), for: .touchUpInside)
        bar.addSubview(fab)
        aiFab = fab

        window.addSubview(bar)
        tabBarView = bar

        updateSelection(0)
    }

    private func createTabButton(tab: (icon: String, iconFilled: String, label: String), index: Int, frame: CGRect) -> UIButton {
        let btn = UIButton(frame: frame)
        btn.tag = index

        let config = UIImage.SymbolConfiguration(pointSize: 20, weight: .medium)
        btn.setImage(UIImage(systemName: tab.icon, withConfiguration: config), for: .normal)
        btn.tintColor = inkColor

        btn.addTarget(self, action: #selector(tabTapped(_:)), for: .touchUpInside)
        return btn
    }

    @objc private func tabTapped(_ sender: UIButton) {
        let index = sender.tag
        updateSelection(index)
        TabBarBridge.shared.onTabSelected?(KotlinInt(integerLiteral: index))
    }

    @objc private func chatTapped() {
        TabBarBridge.shared.onChatTapped?()
    }

    func updateSelection(_ index: Int) {
        selectedIndex = index
        for (i, btn) in buttons.enumerated() {
            let tab = tabs[i]
            let isSelected = (i == index)
            let config = UIImage.SymbolConfiguration(pointSize: 20, weight: isSelected ? .semibold : .medium)

            if i == 3 && !userInitial.isEmpty {
                // Profile tab — show initial circle
                btn.setImage(nil, for: .normal)
                btn.setTitle(userInitial, for: .normal)
                btn.titleLabel?.font = .systemFont(ofSize: 11, weight: .black)
                btn.setTitleColor(isSelected ? .white : inkColor, for: .normal)
                btn.backgroundColor = isSelected ? coralColor : .clear
                btn.layer.cornerRadius = 12
            } else {
                btn.setTitle(nil, for: .normal)
                btn.backgroundColor = .clear
                btn.layer.cornerRadius = 0
                let iconName = isSelected ? tab.iconFilled : tab.icon
                btn.setImage(UIImage(systemName: iconName, withConfiguration: config), for: .normal)
                btn.tintColor = isSelected ? coralColor : inkColor
            }
        }
    }

    func setUserInitial(_ initial: String) {
        userInitial = initial
        updateSelection(selectedIndex)
    }
}
