//
//  MessageViews.swift
//  Sendro
//
//  PROTOCOL.md §11 — ephemeral text, both directions.
//
//  Receiving: a top-anchored glass card over whatever tab is showing, with
//  the sender, the (selectable) text, Copy and Close. Closing frees it for
//  good — the only place a message ever lived was MessageCenter's array.
//  Sending: a composer sheet that POSTs the text and then clears the field.
//
//  Nothing in this file writes to UserDefaults, the Keychain, HistoryStore or
//  any file. Copy hands the text to UIPasteboard because the user asked for
//  it; that is the single exit from RAM, and it is user-initiated.
//

import SwiftUI
import UIKit

// MARK: - Incoming: the card stack

/// Overlays the pending message stack. Newest message is the front card;
/// closing it reveals the next one.
struct MessageInboxOverlay: View {

    @EnvironmentObject private var messages: MessageCenter

    var body: some View {
        VStack(spacing: 0) {
            if let front = messages.inbox.last {
                MessageCard(message: front,
                            remaining: max(0, messages.inbox.count - 1),
                            onClose: { messages.dismiss(id: front.messageId) })
                    .id(front.messageId)
                    // Peeking edges for the queued ones. A background never
                    // participates in layout, so these can never stretch the
                    // card (the flexible-child trap).
                    .background(alignment: .top) {
                        ForEach(Array(0..<hintLayerCount), id: \.self) { layer in
                            let step = CGFloat(layer + 1)
                            RoundedRectangle(cornerRadius: 26, style: .continuous)
                                .fill(Color.white.opacity(0.07 - Double(layer) * 0.025))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 26, style: .continuous)
                                        .strokeBorder(Color.white.opacity(0.09), lineWidth: 0.5)
                                )
                                .padding(.horizontal, step * 9)
                                .offset(y: step * 9)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .padding(.bottom, 24)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
            Spacer(minLength: 0)
        }
        .animation(.spring(response: 0.36, dampingFraction: 0.86), value: messages.inbox.count)
    }

    private var hintLayerCount: Int {
        min(2, max(0, messages.inbox.count - 1))
    }
}

/// One message. Owns only its transient "Copied" flash.
struct MessageCard: View {

    let message: Message
    let remaining: Int
    let onClose: () -> Void

    @State private var copied = false

    /// Long messages scroll inside a bounded box; short ones (the common
    /// case — a link, a code) just lay out at their natural height.
    private var isLong: Bool { message.text.count > 240 }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header
            textBlock
            actions
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard(cornerRadius: 26)
    }

    private var header: some View {
        HStack(spacing: 11) {
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Theme.iris.opacity(0.18))
                Image(systemName: "text.bubble")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(Theme.irisBright)
            }
            .frame(width: 38, height: 38)

            VStack(alignment: .leading, spacing: 2) {
                Text("\(message.senderName) sent you text")
                    .font(Theme.sans(15, .semibold))
                    .foregroundColor(Theme.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                Text("\(HomeView.timeString(message.sentAtMs)) · not saved anywhere")
                    .font(Theme.mono(10))
                    .foregroundColor(Theme.textTertiary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }

            Spacer(minLength: 0)

            if remaining > 0 {
                Text("+\(remaining)")
                    .font(Theme.mono(10.5, .medium))
                    .foregroundColor(Theme.irisSoft)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 5)
                    .background(Capsule().fill(Theme.iris.opacity(0.14)))
                    .accessibilityLabel("\(remaining) more messages waiting")
            }
        }
    }

    @ViewBuilder
    private var textBlock: some View {
        if isLong {
            ScrollView {
                messageText
                    .padding(.trailing, 2)
            }
            .frame(height: 190)
            .scrollIndicators(.hidden)
        } else {
            messageText
        }
    }

    private var messageText: some View {
        Text(message.text)
            .font(Theme.sans(15))
            .foregroundColor(Theme.textBase.opacity(0.95))
            .lineSpacing(2)
            .textSelection(.enabled)
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
            .multilineTextAlignment(.leading)
    }

    private var actions: some View {
        HStack(spacing: 10) {
            Button {
                UIPasteboard.general.string = message.text
                withAnimation(.easeOut(duration: 0.15)) { copied = true }
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 1_600_000_000)
                    withAnimation(.easeOut(duration: 0.15)) { copied = false }
                }
            } label: {
                HStack(spacing: 7) {
                    Image(systemName: copied ? "checkmark" : "doc.on.doc")
                        .font(.system(size: 13, weight: .bold))
                    Text(copied ? "Copied" : "Copy")
                        .font(Theme.sans(15.5, .semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
                .foregroundColor(Theme.onAccent)
                .padding(.horizontal, 8)
                .frame(maxWidth: .infinity)
                .frame(height: 46)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(copied ? Theme.teal : Theme.iris))
                .shadow(color: (copied ? Theme.teal : Theme.iris).opacity(0.35),
                        radius: 12, x: 0, y: 7)
            }
            .buttonStyle(PressableButtonStyle())

            Button(action: onClose) {
                GhostPillLabel(title: "Close", height: 46)
            }
            .buttonStyle(PressableButtonStyle())
            .frame(maxWidth: 120)
        }
    }
}

// MARK: - Outgoing: the composer

/// Composer for §11.2. The text lives in this view's @State and in the
/// request body — nowhere else. Dismissing throws it away.
struct MessageComposerSheet: View {

    let initialText: String
    let hostName: String
    let hostId: String?

    @EnvironmentObject private var engine: TransferEngine
    @Environment(\.dismiss) private var dismiss

    @State private var text: String
    @State private var sending = false
    @State private var sent = false
    @State private var errorMessage: String?
    @FocusState private var editing: Bool

    init(initialText: String, hostName: String, hostId: String?) {
        self.initialText = initialText
        self.hostName = hostName
        self.hostId = hostId
        _text = State(initialValue: initialText)
    }

    private var byteCount: Int { text.utf8.count }
    private var overLimit: Bool { byteCount > sendroMessageByteLimit }
    private var canSend: Bool {
        hostId != nil && !sending && !overLimit
            && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        ZStack(alignment: .top) {
            LinearGradient(colors: [Color(red: 0x1C / 255, green: 0x1E / 255, blue: 0x2C / 255),
                                    Color(red: 0x0E / 255, green: 0x0F / 255, blue: 0x16 / 255)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { editing = false }

            VStack(alignment: .leading, spacing: 0) {
                Text("Send text")
                    .font(Theme.sans(24, .semibold))
                    .foregroundColor(Theme.textPrimary)

                Text("To \(hostName). It pops up on the PC as a card — copy it, close it, gone. Nothing is saved on either side.")
                    .font(Theme.sans(13))
                    .foregroundColor(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 8)

                editor
                    .padding(.top, 18)

                counterRow
                    .padding(.top, 10)

                if let errorMessage = errorMessage {
                    Text(errorMessage)
                        .font(Theme.sans(12.5))
                        .foregroundColor(Theme.danger)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 12)
                }

                Spacer(minLength: 18)

                HStack(spacing: 10) {
                    Button(action: send) {
                        HStack(spacing: 7) {
                            if sending {
                                ProgressView()
                                    .progressViewStyle(.circular)
                                    .tint(Theme.onAccent)
                            } else if sent {
                                Image(systemName: "checkmark")
                                    .font(.system(size: 13, weight: .bold))
                            }
                            Text(sent ? "Sent" : "Send")
                                .font(Theme.sans(15.5, .semibold))
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        }
                        .foregroundColor(Theme.onAccent)
                        .padding(.horizontal, 8)
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .background(RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .fill(sent ? Theme.teal : Theme.iris))
                        .shadow(color: (sent ? Theme.teal : Theme.iris).opacity(0.35),
                                radius: 12, x: 0, y: 7)
                    }
                    .buttonStyle(PressableButtonStyle())
                    .disabled(!canSend && !sent)
                    .opacity((canSend || sent) ? 1 : 0.45)

                    Button {
                        text = ""
                        dismiss()
                    } label: {
                        GhostPillLabel(title: "Cancel")
                    }
                    .buttonStyle(PressableButtonStyle())
                    .frame(maxWidth: 120)
                }
                .padding(.bottom, 8)
            }
            .padding(.horizontal, 20)
            .padding(.top, 26)
            .padding(.bottom, 20)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
        .preferredColorScheme(.dark)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") { editing = false }
                    .foregroundColor(Theme.irisSoft)
            }
        }
        .onAppear {
            // Land straight in the field unless we arrived with a paste.
            if initialText.isEmpty { editing = true }
        }
    }

    private var editor: some View {
        TextEditor(text: $text)
            .focused($editing)
            .font(Theme.sans(15))
            .foregroundColor(Theme.textPrimary)
            .tint(Theme.iris)
            .scrollContentBackground(.hidden)
            .padding(8)
            .frame(minHeight: 132, maxHeight: 260)
            .glassRow(cornerRadius: 18, fillOpacity: 0.05, borderOpacity: 0.1)
            .overlay(alignment: .topLeading) {
                if text.isEmpty {
                    Text("Paste a link, a path, a code…")
                        .font(Theme.sans(15))
                        .foregroundColor(Theme.textBase.opacity(0.3))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 16)
                        .allowsHitTesting(false)
                }
            }
    }

    private var counterRow: some View {
        HStack(spacing: 8) {
            Text("\(byteCount) / \(sendroMessageByteLimit) bytes")
                .font(Theme.mono(10.5))
                .foregroundColor(overLimit ? Theme.danger : Theme.textTertiary)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Spacer(minLength: 0)
            if overLimit {
                Text("Too long")
                    .font(Theme.mono(10.5, .medium))
                    .foregroundColor(Theme.danger)
                    .lineLimit(1)
            } else if hostId == nil {
                Text("No PC online")
                    .font(Theme.mono(10.5, .medium))
                    .foregroundColor(Theme.warn)
                    .lineLimit(1)
            }
        }
        .padding(.horizontal, 2)
    }

    private func send() {
        guard let hostId = hostId, canSend else { return }
        let payload = text
        editing = false
        sending = true
        errorMessage = nil
        Task { @MainActor in
            let failure = await engine.sendMessage(payload, toHostId: hostId)
            sending = false
            if let failure {
                errorMessage = failure
                return
            }
            // Success: clear the field immediately, flash the confirmation,
            // then close. Nothing is written anywhere.
            text = ""
            withAnimation(.easeOut(duration: 0.15)) { sent = true }
            try? await Task.sleep(nanoseconds: 800_000_000)
            dismiss()
        }
    }
}
