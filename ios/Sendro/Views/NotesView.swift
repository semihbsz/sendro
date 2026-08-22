//
//  NotesView.swift
//  Sendro
//
//  The 24-hour notes shelf. Every text you send or receive lands here and
//  deletes itself a day later — long enough to copy that Wi-Fi password
//  twice, short enough that the shelf never becomes an archive.
//
//  Reading surface only: composing still happens from the Send tab, so
//  there is exactly one place in the app that puts text on the wire.
//

import SwiftUI
import UIKit

struct NotesView: View {

    @EnvironmentObject private var notes: NoteStore

    @State private var confirmClearAll = false
    @State private var copiedNoteId: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .padding(.horizontal, 20)
                .padding(.top, 12)

            if notes.isEmpty {
                emptyState
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 9) {
                        ForEach(notes.newestFirst) { note in
                            noteCard(note)
                        }
                        footerNote
                            .padding(.top, 6)
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 18)
                    .padding(.bottom, 130)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .scrollIndicators(.hidden)
            }
        }
        .confirmationDialog("Clear every note?",
                            isPresented: $confirmClearAll,
                            titleVisibility: .visible) {
            Button("Clear Notes", role: .destructive) {
                notes.clearAll()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("They would go on their own within 24 hours anyway.")
        }
        .onAppear {
            notes.prune()
            notes.startPruning()
        }
    }

    // MARK: Header

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            Text("Notes")
                .font(Theme.sans(34, .semibold))
                .foregroundColor(Theme.textPrimary)
            Spacer()
            if !notes.isEmpty {
                Button {
                    confirmClearAll = true
                } label: {
                    Text("Clear")
                        .font(Theme.sans(12, .medium))
                        .foregroundColor(Theme.irisSoft)
                }
                .buttonStyle(PressableButtonStyle())
            }
        }
    }

    // MARK: Empty

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "note.text")
                .font(.system(size: 26, weight: .light))
                .foregroundColor(Theme.textBase.opacity(0.35))
            Text("Nothing on the shelf")
                .font(Theme.sans(17, .semibold))
                .foregroundColor(Theme.textBase.opacity(0.8))
            Text("Text you send from the Send tab, and text your computer sends here, stays on this iPhone for 24 hours and then deletes itself.")
                .font(Theme.sans(13))
                .foregroundColor(Theme.textTertiary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 44)
            Spacer()
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: One note

    private func noteCard(_ note: Note) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 7) {
                Image(systemName: note.direction == .incoming
                      ? "arrow.down.left"
                      : "arrow.up.right")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(note.direction == .incoming ? Theme.iris : Theme.teal)
                Text(note.direction == .incoming
                     ? "from \(note.peerName)"
                     : "to \(note.peerName)")
                    .font(Theme.mono(10.5))
                    .foregroundColor(Theme.textTertiary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Spacer(minLength: 6)
                Text("\(NoteStore.hoursLeft(for: note)) h left")
                    .font(Theme.mono(10))
                    .foregroundColor(Theme.textBase.opacity(0.3))
                    .lineLimit(1)
            }

            Text(note.text)
                .font(Theme.sans(14))
                .foregroundColor(Theme.textBase.opacity(0.93))
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 8) {
                Button {
                    UIPasteboard.general.string = note.text
                    withAnimation(.easeOut(duration: 0.15)) { copiedNoteId = note.id }
                    Task { @MainActor in
                        try? await Task.sleep(nanoseconds: 1_200_000_000)
                        if copiedNoteId == note.id {
                            withAnimation(.easeOut(duration: 0.15)) { copiedNoteId = nil }
                        }
                    }
                } label: {
                    // GhostPillLabel stretches to fill; the fixed width keeps
                    // the two pills side by side instead of splitting the row.
                    GhostPillLabel(title: copiedNoteId == note.id ? "Copied" : "Copy",
                                   height: 30)
                        .frame(width: 92)
                }
                .buttonStyle(PressableButtonStyle())

                Button {
                    withAnimation(.easeOut(duration: 0.18)) { notes.remove(id: note.id) }
                } label: {
                    GhostPillLabel(title: "Delete",
                                   textColor: Theme.danger,
                                   height: 30)
                        .frame(width: 92)
                }
                .buttonStyle(PressableButtonStyle())

                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .glassRow(cornerRadius: 18)
    }

    private var footerNote: some View {
        Text("Notes live on this iPhone only. Your computer keeps nothing — it forgets each message the moment it is delivered.")
            .font(Theme.sans(11.5))
            .foregroundColor(Theme.textBase.opacity(0.32))
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 2)
    }
}
