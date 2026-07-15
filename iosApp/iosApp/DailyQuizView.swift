import SwiftUI
import UIKit
import Combine

// MARK: - Wire payloads (mirror get_daily_quiz / submit_quiz + augmented rank)

private struct QuizAnswerJson: Codable {
    let text: String
    let correct: Bool
}

private struct QuizQuestionJson: Codable, Identifiable {
    let id: String
    let difficulty: String
    let question: String
    let answers: [QuizAnswerJson]
    let max_points: Int
}

private struct DailyQuizJson: Codable {
    let available: Bool
    let state: String?
    let questions: [QuizQuestionJson]
}

private struct AnswerOut: Codable {
    let question_id: String
    let selected_index: Int
    let ms_remaining: Int
}

private struct QuizBreakdownJson: Codable, Identifiable {
    var id: String { question_id }
    let question_id: String
    let difficulty: String
    let selected_index: Int
    let correct: Bool
    let ms_remaining: Int
    let base: Int
    let points: Int
}

/// The augmented submit result (see DailyQuizViewModel.submitAnswersJson).
private struct QuizResultJson: Codable {
    let score: Int
    let correct_count: Int
    let streak: Int
    let best_all_time: Int
    let is_first: Bool
    let state: String?
    let quiz_date: String?
    let rank: Int
    let rank_location: String?
    let percentile: Int
    let share_text: String
    let bo_message: String
    let breakdown: [QuizBreakdownJson]
}

// MARK: - Theming

private enum C {
    static let coral   = Color(hex2: 0xFF6B6B)   // kept for semantic "wrong" + timer urgency
    static let coralDk = Color(hex2: 0xE85555)
    static let coralLt = Color(hex2: 0xFFE8E8)
    static let amber   = Color(hex2: 0xFFB830)   // Daily Local brand accent
    static let amberDk = Color(hex2: 0xE6A020)
    static let amberLt = Color(hex2: 0xFFF4DC)
    static let ink     = Color(hex2: 0x1A1523)
    static let ink2    = Color(hex2: 0x4A4458)
    static let ink3    = Color(hex2: 0x8A849A)
    static let snow    = Color(hex2: 0xFAF8FC)
    static let sage    = Color(hex2: 0x4ECBA0)
    static let sun     = Color(hex2: 0xFFB830)
    static let white   = Color.white
}

private enum F {
    static func heading(_ size: CGFloat, _ weight: Font.Weight = .black) -> Font {
        Font.custom("Fraunces", size: size).weight(weight)
    }
    static func body(_ size: CGFloat, _ weight: Font.Weight = .medium) -> Font {
        Font.custom("Nunito", size: size).weight(weight)
    }
}

private func diffMult(_ d: String) -> Int { d == "medium" ? 2 : (d == "hard" ? 3 : 1) }

private func stampDate(_ date: String?) -> String {
    let months = ["JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC"]
    guard let parts = date?.split(separator: "-"), parts.count == 3,
          let m = Int(parts[1]), let d = Int(parts[2]), m >= 1, m <= 12 else { return "" }
    return "\(months[m - 1]) \(d)"
}

// MARK: - View

/// Native Daily Local quiz (iOS): draining bar timer to a tenth-second, a 2s
/// "read up" delay per question, tap-to-answer with feedback + auto-advance, and
/// a screenshot-ready postcard rendered to a shareable image.
struct DailyQuizView: View {
    let quizJson: String
    /// (answersJson, done) — runs submit_quiz; `done` receives the augmented result JSON.
    let onSubmit: (String, @escaping (String?) -> Void) -> Void
    let onDismiss: () -> Void

    private enum Phase { case question, submitting, results }
    // Per-question intro: Bo big -> Bo shrinks + question streams -> read-in
    // countdown chip -> options float in and the clock goes live.
    private enum IntroStep { case boBig, streaming, readIn, live }

    private let questionMs: Double = 10_000
    private let revealHold: Double = 1.1

    @State private var quiz: DailyQuizJson?
    @State private var phase: Phase = .question
    @State private var questionIndex = 0
    @State private var recorded: [AnswerOut] = []
    @State private var live = false            // true only once the clock is running
    @State private var intro: IntroStep = .boBig
    @State private var streamedCount = 0       // chars of the question revealed
    @State private var readInSecs = 2
    @State private var deadline = Date()
    @State private var now = Date()
    @State private var revealing = false
    @State private var revealSelected: Int? = nil
    @State private var result: QuizResultJson?
    @State private var submitError = false
    @State private var displayScore = 0
    // Results reveal: rows appear one at a time; per correct row the time counts
    // 10s -> time-left (rowProg 0->1), then the points show and add to the total.
    @State private var rowsShown = 0
    @State private var rowProg: [Double] = [0, 0, 0]     // 0->1 count-down phase (yellow)
    @State private var rowRevealed: [Bool] = [false, false, false]
    @State private var rowBlinkOp: [Double] = [1, 1, 1]  // wrong-answer "0" blink opacity
    @State private var boStreamed = 0                    // chars of Bo's reaction revealed
    @State private var rowsDone = false                  // all rows revealed → float Bo up
    @State private var shareVisible = false              // Bo finished typing → float Share up

    private let ticker = Timer.publish(every: 0.05, on: .main, in: .common).autoconnect()
    private let tick = UIImpactFeedbackGenerator(style: .light)

    private var remainingMs: Double {
        live ? max(0, deadline.timeIntervalSince(now) * 1000) : questionMs
    }

    var body: some View {
        ZStack {
            C.snow.ignoresSafeArea()
            Group {
                if let quiz = quiz, !quiz.questions.isEmpty {
                    switch phase {
                    case .question: questionStage(quiz)
                    case .submitting: ProgressView().tint(C.amber)
                    case .results: resultsStage()
                    }
                } else {
                    unavailableStage()
                }
            }
            .padding(.horizontal, 22)
            .padding(.vertical, 18)
        }
        .onAppear(perform: decode)
        .onReceive(ticker) { t in
            guard phase == .question, live, !revealing else { return }
            now = t
            if remainingMs <= 0 { handleTimeout() }
        }
        // Lock swipe-to-dismiss only while a question is active (prevents closing
        // mid-quiz to restart with a fresh timer). Results/unavailable can swipe away.
        .interactiveDismissDisabled(phase == .question && quiz != nil)
    }

    // MARK: Question stage

    private func questionStage(_ quiz: DailyQuizJson) -> some View {
        let q = quiz.questions[questionIndex]
        let correctIndex = q.answers.firstIndex(where: { $0.correct }) ?? -1
        let frac = min(1, max(0, remainingMs / questionMs))
        return VStack(alignment: .leading, spacing: 0) {
            // Draining bar
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(C.coralLt)
                    Capsule().fill(barColor).frame(width: geo.size.width * CGFloat(frac))
                }
            }
            .frame(height: 8)
            .padding(.bottom, 12)

            HStack(spacing: 8) {
                Text("Question \(questionIndex + 1) of \(quiz.questions.count)")
                    .font(F.body(13, .semibold)).foregroundColor(C.ink3)
                difficultyBadge(q.difficulty)
                Spacer()
                Text("\(tenthsLabel) left").font(F.heading(14, .black)).foregroundColor(barColor)
            }
            .padding(.bottom, 16)

            // Bo types the question: sits to the LEFT of the first line and shrinks
            // in place from big. Keeps the question compact vertically.
            HStack(alignment: .top, spacing: 12) {
                Image("BoDefaultClean").resizable().scaledToFit()
                    .frame(width: intro == .boBig ? 96 : 38, height: intro == .boBig ? 96 : 38)
                if intro != .boBig {
                    Text(typed(q.question, streamedCount)).font(F.heading(23, .bold)).foregroundColor(C.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
            .padding(.bottom, 10)

            if intro == .readIn {
                Text("Timer starts in \(readInSecs)s")
                    .font(F.body(13, .bold)).foregroundColor(C.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 11)
                    .background(C.ink)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .padding(.bottom, 10)
                    .transition(.scale.combined(with: .opacity))
            }

            if intro == .live {
                VStack(spacing: 10) {
                    ForEach(Array(q.answers.enumerated()), id: \.offset) { i, ans in
                        answerPill(text: ans.text, state: pillState(i: i, correctIndex: correctIndex), enabled: !revealing) {
                            handleAnswer(i)
                        }
                    }
                }
                .padding(.top, 6)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            Spacer()
        }
        .padding(.top, 18)
    }

    private func difficultyBadge(_ d: String) -> some View {
        let c: Color = d == "hard" ? C.coral : (d == "medium" ? C.sun : C.sage)
        return Text(d.uppercased())
            .font(F.body(10, .bold)).foregroundColor(c)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(c.opacity(0.16)).clipShape(Capsule())
    }

    private var tenthsLabel: String {
        let t = Int(remainingMs / 100)      // 0...100
        return "\(t / 10).\(t % 10)s"
    }

    private var barColor: Color {
        remainingMs <= 3000 ? C.coral : (remainingMs <= 5000 ? C.sun : C.sage)
    }

    private enum PillState { case idle, correct, wrong, dim }

    private func pillState(i: Int, correctIndex: Int) -> PillState {
        if !revealing { return .idle }
        if i == correctIndex { return .correct }
        if i == revealSelected { return .wrong }
        return .dim
    }

    private func answerPill(text: String, state: PillState, enabled: Bool, tap: @escaping () -> Void) -> some View {
        let bg: Color = state == .correct ? C.sage : (state == .wrong ? C.coral : C.white)
        let fg: Color = (state == .correct || state == .wrong) ? C.white : (state == .dim ? C.ink3 : C.ink)
        return Button(action: { if enabled { tap() } }) {
            HStack {
                Text(text).font(F.body(16, .bold)).foregroundColor(fg)
                Spacer()
            }
            .padding(.horizontal, 18)
            .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .background(bg)
            .overlay(RoundedRectangle(cornerRadius: 14).stroke((state == .idle || state == .dim) ? C.ink3 : Color.clear, lineWidth: 1.5))
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .animation(.easeInOut(duration: 0.22), value: state)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    // MARK: Results stage

    private func resultsStage() -> some View {
        Group {
            if submitError && result == nil {
                VStack(spacing: 14) {
                    Spacer().frame(height: 20)
                    Text("Couldn't save your score").font(F.heading(20, .bold)).foregroundColor(C.ink)
                    Text("Check your connection and try again.").font(F.body(14)).foregroundColor(C.ink3)
                    primaryButton("Retry") { submit() }
                    Spacer()
                }.frame(maxWidth: .infinity)
            } else if let r = result {
                ScrollView {
                    VStack(spacing: 18) {
                        postcard(r, score: displayScore)
                        // Bo + Share stay hidden while the rows reveal. Once done,
                        // Bo floats up and types; then Share floats up.
                        if rowsDone && !r.bo_message.isEmpty {
                            boReaction(r)
                                .transition(.move(edge: .bottom).combined(with: .opacity))
                        }
                        if shareVisible {
                            primaryButton("Share") { shareCard(r) }
                                .transition(.move(edge: .bottom).combined(with: .opacity))
                        }
                    }
                }
                .onAppear { runResults(r) }
            }
        }
    }

    /// The postcard. `full` renders every row resolved (used for the shared image);
    /// on-screen it animates in one row at a time.
    private func postcard(_ r: QuizResultJson, score: Int, full: Bool = false) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                Rectangle().fill(C.sage)
                Rectangle().fill(C.sun)
                Rectangle().fill(C.coral)
            }.frame(height: 6)
            VStack(alignment: .leading, spacing: 0) {
                Text("DAILY LOCAL").font(F.heading(13, .black)).foregroundColor(C.amberDk).tracking(1)
                Text("\(score)").font(F.heading(52, .black)).foregroundColor(C.ink).padding(.top, 10)
                Text(r.is_first ? "FOMO EARNED TODAY" : "FOMO EARNED TODAY · BEST ALL TIME \(r.best_all_time)")
                    .font(F.body(11, .bold)).foregroundColor(C.ink3).tracking(0.5)
                VStack(spacing: 12) {
                    ForEach(Array(r.breakdown.enumerated()), id: \.offset) { i, b in
                        if full || i < rowsShown {
                            resultRow(i, b, full: full)
                                .transition(.opacity.combined(with: .move(edge: .leading)))
                        }
                    }
                }.padding(.top, 18)
            }
            .padding(20)
        }
        .background(C.white)
        .clipShape(RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(C.amberLt, lineWidth: 1))
    }

    /// One question's result. While counting: a yellow ring + yellow bar drain,
    /// and the seconds + points count down together. On reveal it turns green (✓)
    /// or red (✗, points blink to 0).
    private func resultRow(_ i: Int, _ b: QuizBreakdownJson, full: Bool) -> some View {
        let timeLeft = Double(b.ms_remaining) / 1000.0
        let frac = min(1, max(0, Double(b.ms_remaining) / 10000.0))
        let prog = full ? 1.0 : rowProg[i]
        let revealed = full ? true : rowRevealed[i]
        let mult = diffMult(b.difficulty)
        let shownTime = 10.0 - (10.0 - timeLeft) * prog        // 10s -> time-left
        let baseNow = Int((shownTime * 10).rounded())          // 100 -> base
        let totalNow = baseNow * mult                          // max -> total
        let barFrac = 1.0 - (1.0 - frac) * prog                // full -> time fraction
        let accent: Color = !revealed ? C.sun : (b.correct ? C.sage : C.coral)
        return HStack(spacing: 10) {
            ZStack {
                if revealed {
                    Circle().fill(accent).frame(width: 26, height: 26)
                    Text(b.correct ? "✓" : "✗").font(F.heading(13, .black)).foregroundColor(C.white)
                } else {
                    Circle().stroke(C.sun, lineWidth: 3).frame(width: 26, height: 26)
                }
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(C.amberLt)
                    Capsule().fill(accent).frame(width: geo.size.width * CGFloat(barFrac))
                }
            }.frame(height: 8)
            Text(String(format: "%.1fs", shownTime)).font(F.body(12, .bold)).foregroundColor(C.ink3)
                .frame(width: 40, alignment: .trailing)
            Group {
                if !revealed {
                    Text("\(totalNow)").font(F.heading(14, .black)).foregroundColor(C.ink3)
                } else if b.correct {
                    HStack(spacing: 3) {
                        Text("\(b.base) × \(mult) =").font(F.body(11, .semibold)).foregroundColor(C.ink3)
                        Text("\(b.points)").font(F.heading(14, .black)).foregroundColor(C.ink)
                    }
                } else {
                    Text("0").font(F.heading(14, .black)).foregroundColor(C.coral).opacity(full ? 1 : rowBlinkOp[i])
                }
            }
            .frame(width: 96, alignment: .trailing)
        }
    }

    /// Bo appears beneath the results and types out his reaction line.
    private func boReaction(_ r: QuizResultJson) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image("BoDefaultClean").resizable().scaledToFit().frame(width: 40, height: 40)
            Text(typed(r.bo_message, boStreamed))
                .font(F.body(14, .semibold)).foregroundColor(C.ink2)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 4)
            Spacer(minLength: 0)
        }
    }

    /// Reveals a streaming string without reflow: the FULL text is always laid
    /// out (so line breaks + height are fixed from the first frame), and only the
    /// not-yet-typed tail is made transparent. Revealing more characters never
    /// re-wraps, so no line ever jumps as a word crosses the line boundary.
    private func typed(_ full: String, _ shown: Int) -> AttributedString {
        var a = AttributedString(full)
        let n = max(0, min(shown, full.count))
        if n < full.count {
            let idx = a.index(a.startIndex, offsetByCharacters: n)
            a[idx...].foregroundColor = .clear
        }
        return a
    }

    private func streamBo(_ text: String) {
        boStreamed = 0
        let n = text.count
        guard n > 0 else { return }
        for i in 1...n {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.025 * Double(i)) { boStreamed = i }
        }
    }

    private func unavailableStage() -> some View {
        VStack(spacing: 12) {
            Spacer()
            Text("Today's quiz isn't ready").font(F.heading(20, .bold)).foregroundColor(C.ink)
            Text("Check back soon.").font(F.body(14)).foregroundColor(C.ink3)
            secondaryButton("Close") { onDismiss() }
            Spacer()
        }.frame(maxWidth: .infinity)
    }

    // Matches the event-page primary CTA (3D hard-shadow), in Daily Local amber.
    private func primaryButton(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(F.heading(16, .heavy))
                .foregroundColor(C.ink)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 15)
                .background(C.amber)
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(C.amberDk, lineWidth: 2))
                .shadow(color: C.amberDk.opacity(0.4), radius: 0, x: 0, y: 4)
        }.buttonStyle(.plain)
    }

    private func secondaryButton(_ title: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(F.body(16, .bold)).foregroundColor(C.amberDk)
                .frame(maxWidth: .infinity, minHeight: 52)
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(C.amber, lineWidth: 1.5))
        }.buttonStyle(.plain)
    }

    // MARK: Logic

    private func decode() {
        guard let data = quizJson.data(using: .utf8),
              let parsed = try? JSONDecoder().decode(DailyQuizJson.self, from: data) else { return }
        quiz = parsed
        beginQuestion()
    }

    private func beginQuestion() {
        live = false
        intro = .boBig
        streamedCount = 0
        readInSecs = 2
        now = Date()
        // Bo lands big, then shrinks to the corner as the question streams in.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) {
            guard phase == .question, !revealing else { return }
            withAnimation(.spring(response: 0.5, dampingFraction: 0.72)) { intro = .streaming }
            streamQuestion()
        }
    }

    private func streamQuestion() {
        guard let q = quiz, questionIndex < q.questions.count else { return }
        let full = q.questions[questionIndex].question
        func step() {
            guard intro == .streaming, phase == .question else { return }
            if streamedCount < full.count {
                streamedCount += 1
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.02) { step() }
            } else {
                startReadIn()
            }
        }
        step()
    }

    private func startReadIn() {
        readInSecs = 2
        withAnimation { intro = .readIn }
        tickReadIn()
    }

    private func tickReadIn() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            guard intro == .readIn, phase == .question, !revealing else { return }
            if readInSecs > 1 {
                readInSecs -= 1
                tickReadIn()
            } else {
                // Chip disappears, options float in, clock goes live.
                withAnimation(.spring(response: 0.45, dampingFraction: 0.75)) { intro = .live }
                now = Date()
                deadline = Date().addingTimeInterval(questionMs / 1000)
                live = true
            }
        }
    }

    private func handleAnswer(_ i: Int) {
        guard phase == .question, live, !revealing, let q = quiz?.questions[questionIndex] else { return }
        recorded.append(AnswerOut(question_id: q.id, selected_index: i, ms_remaining: Int(remainingMs)))
        reveal(selected: i)
    }

    private func handleTimeout() {
        guard phase == .question, !revealing, let q = quiz?.questions[questionIndex] else { return }
        recorded.append(AnswerOut(question_id: q.id, selected_index: -1, ms_remaining: 0))
        reveal(selected: nil)
    }

    private func reveal(selected: Int?) {
        revealSelected = selected
        revealing = true
        DispatchQueue.main.asyncAfter(deadline: .now() + revealHold) { advance() }
    }

    private func advance() {
        guard revealing else { return }
        revealing = false
        revealSelected = nil
        let total = quiz?.questions.count ?? 0
        if recorded.count >= total {
            submit()
        } else {
            questionIndex += 1
            beginQuestion()
        }
    }

    private func submit() {
        phase = .submitting
        submitError = false
        guard let data = try? JSONEncoder().encode(recorded),
              let answersJson = String(data: data, encoding: .utf8) else {
            submitError = true; phase = .results; return
        }
        onSubmit(answersJson) { resultJson in
            DispatchQueue.main.async {
                if let rj = resultJson, let rd = rj.data(using: .utf8),
                   let comp = try? JSONDecoder().decode(QuizResultJson.self, from: rd) {
                    result = comp
                    phase = .results
                } else {
                    submitError = true
                    phase = .results
                }
            }
        }
    }

    /// Reveal each result in turn: row in -> (if correct) time counts 10s->left +
    /// bar drains -> points show and add to the running total -> next row.
    private func runResults(_ r: QuizResultJson) {
        rowsShown = 0
        displayScore = 0
        rowProg = [0, 0, 0]
        rowRevealed = [false, false, false]
        rowBlinkOp = [1, 1, 1]
        rowsDone = false
        shareVisible = false
        boStreamed = 0
        tick.prepare()
        var t = 0.4
        for i in 0..<r.breakdown.count {
            let b = r.breakdown[i]
            DispatchQueue.main.asyncAfter(deadline: .now() + t) {
                withAnimation(.easeOut(duration: 0.3)) { rowsShown = i + 1 }
            }
            // Count phase (both correct + wrong count the seconds/points down).
            // Dense steps (~45fps) for smoothness; haptic only every 4th step.
            let steps = 40, dur = 0.9, startAt = t + 0.2
            for s in 1...steps {
                DispatchQueue.main.asyncAfter(deadline: .now() + startAt + dur * Double(s) / Double(steps)) {
                    rowProg[i] = Double(s) / Double(steps)
                    if s % 4 == 0 { tick.impactOccurred(intensity: 0.4) }
                }
            }
            let revealAt = startAt + dur + 0.15
            DispatchQueue.main.asyncAfter(deadline: .now() + revealAt) {
                withAnimation(.spring(response: 0.4, dampingFraction: 0.7)) { rowRevealed[i] = true }
                if b.correct { addToTotal(b.points) } else { blinkWrong(i) }
            }
            t = revealAt + (b.correct ? 0.7 : 1.0)
        }
        // Sequence the after-reveal beats: rows finish → Bo floats up → Bo types
        // → Share floats up.
        let boText = r.bo_message
        let hasBo = !boText.isEmpty
        // 1) Bo floats up once every row has revealed.
        DispatchQueue.main.asyncAfter(deadline: .now() + t + 0.2) {
            withAnimation(.spring(response: 0.5, dampingFraction: 0.8)) { rowsDone = true }
        }
        // 2) Bo types his line.
        let boStart = t + 0.55
        if hasBo {
            DispatchQueue.main.asyncAfter(deadline: .now() + boStart) { streamBo(boText) }
        }
        // 3) Share floats up after Bo finishes typing (or right after Bo appears
        //    if there's no line).
        let shareAt = hasBo ? boStart + 0.025 * Double(boText.count) + 0.4 : t + 0.6
        DispatchQueue.main.asyncAfter(deadline: .now() + shareAt) {
            withAnimation(.spring(response: 0.5, dampingFraction: 0.8)) { shareVisible = true }
        }
    }

    /// Blink the wrong-answer "0" twice.
    private func blinkWrong(_ i: Int) {
        let seq: [(Double, Double)] = [(0.0, 0.0), (0.14, 1.0), (0.28, 0.0), (0.42, 1.0)]
        for (d, op) in seq {
            DispatchQueue.main.asyncAfter(deadline: .now() + d) {
                withAnimation(.linear(duration: 0.07)) { rowBlinkOp[i] = op }
            }
        }
    }

    /// Count the running total up by `pts`.
    private func addToTotal(_ pts: Int) {
        guard pts > 0 else { return }
        let start = displayScore
        let steps = 28, dur = 0.5
        for s in 1...steps {
            DispatchQueue.main.asyncAfter(deadline: .now() + dur * Double(s) / Double(steps)) {
                displayScore = start + Int((Double(pts) * Double(s) / Double(steps)).rounded())
                if s % 3 == 0 { tick.impactOccurred(intensity: 0.4) }   // haptic every 3rd step
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + dur + 0.02) { displayScore = start + pts }
    }

    /// Share the results as a text message (built in Kotlin, delivered via the bridge).
    private func shareCard(_ r: QuizResultJson) {
        guard let window = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).flatMap({ $0.windows })
            .first(where: { $0.isKeyWindow }) else { return }
        let vc = UIActivityViewController(activityItems: [r.share_text], applicationActivities: nil)
        window.rootViewController?.topMostPresented().present(vc, animated: true)
    }
}

private extension UIViewController {
    func topMostPresented() -> UIViewController {
        var vc: UIViewController = self
        while let p = vc.presentedViewController { vc = p }
        return vc
    }
}

private extension Color {
    init(hex2: UInt) {
        self.init(
            red: Double((hex2 >> 16) & 0xFF) / 255.0,
            green: Double((hex2 >> 8) & 0xFF) / 255.0,
            blue: Double(hex2 & 0xFF) / 255.0
        )
    }
}
