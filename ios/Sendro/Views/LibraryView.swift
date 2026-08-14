//
//  LibraryView.swift
//  Sendro
//
//  Everything that has landed on this iPhone. "All" is the transfer history
//  (with Clear), "Photos" filters what went to the gallery, "Files" is the
//  live Documents/Sendro store with share / save-to-Files / delete.
//

import SwiftUI
import UIKit

struct LibraryView: View {

    enum Filter: String, CaseIterable, Identifiable {
        case all = "All"
        case photos = "Photos"
        case files = "Files"

        var id: String { rawValue }
    }

    @EnvironmentObject private var fileStore: FileStore
    @EnvironmentObject private var history: HistoryStore

    @State private var filter: Filter = .all
    @State private var exportFile: ReceivedFile?
    @State private var deleteCandidate: ReceivedFile?
    @State private var confirmClearHistory = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            headerBlock
                .padding(.horizontal, 20)
                .padding(.top, 12)

            ScrollView {
                VStack(alignment: .leading, spacing: 9) {
                    switch filter {
                    case .all:    allSection
                    case .photos: photosSection
                    case .files:  filesSection
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 18)
                .padding(.bottom, 130)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .scrollIndicators(.hidden)
        }
        .sheet(item: $exportFile) { file in
            DocumentExportPicker(url: file.url)
                .ignoresSafeArea()
        }
        .confirmationDialog("Delete this file?",
                            isPresented: Binding(
                                get: { deleteCandidate != nil },
                                set: { if !$0 { deleteCandidate = nil } }),
                            titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                if let file = deleteCandidate {
                    fileStore.delete(file)
                }
                deleteCandidate = nil
            }
            Button("Cancel", role: .cancel) {
                deleteCandidate = nil
            }
        }
        .confirmationDialog("Clear transfer history?",
                            isPresented: $confirmClearHistory,
                            titleVisibility: .visible) {
            Button("Clear History", role: .destructive) {
                history.clear()
            }
            Button("Cancel", role: .cancel) {}
        }
        .onAppear {
            fileStore.refresh()
        }
    }

    // MARK: Header

    private var headerBlock: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .firstTextBaseline) {
                Text("Library")
                    .font(Theme.sans(34, .semibold))
                    .foregroundColor(Theme.textPrimary)
                Spacer()
                if filter == .all && !history.entries.isEmpty {
                    Button {
                        confirmClearHistory = true
                    } label: {
                        Text("Clear")
                            .font(Theme.sans(12, .medium))
                            .foregroundColor(Theme.irisSoft)
                    }
                    .buttonStyle(PressableButtonStyle())
                }
                if filter == .files {
                    Button {
                        fileStore.refresh()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(Theme.irisSoft)
                    }
                    .buttonStyle(PressableButtonStyle())
                    .accessibilityLabel("Refresh")
                }
            }

            filterBar
        }
    }

    private var filterBar: some View {
        HStack(spacing: 6) {
            ForEach(Filter.allCases) { item in
                Button {
                    withAnimation(.easeOut(duration: 0.18)) { filter = item }
                } label: {
                    Text(item.rawValue)
                        .font(Theme.sans(12.5, filter == item ? .semibold : .medium))
                        .foregroundColor(filter == item
                                         ? Theme.textBase
                                         : Theme.textBase.opacity(0.45))
                        .frame(maxWidth: .infinity)
                        .frame(height: 32)
                        .background(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .fill(filter == item ? Color.white.opacity(0.1) : Color.clear)
                        )
                }
                .buttonStyle(PressableButtonStyle())
            }
        }
        .padding(4)
        .glassRow(cornerRadius: 14, fillOpacity: 0.05, borderOpacity: 0.08)
    }

    // MARK: All (history)

    @ViewBuilder
    private var allSection: some View {
        if history.entries.isEmpty {
            emptyState(title: "Nothing here yet",
                       message: "Send a file from Sendro on your PC and every transfer — saved, failed or declined — shows up here.")
        } else {
            ForEach(history.entries) { entry in
                historyRow(entry)
            }
        }
    }

    private func historyRow(_ entry: HistoryEntry) -> some View {
        HStack(spacing: 13) {
            FileBadge(fileName: entry.fileName, side: 44, cornerRadius: 13)
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.fileName)
                    .font(Theme.sans(15, .medium))
                    .foregroundColor(Theme.textBase.opacity(0.93))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(historySubtitle(entry))
                    .font(Theme.mono(10.5))
                    .foregroundColor(Theme.textTertiary)
                    .lineLimit(1)
            }
            Spacer()
            outcomeChip(entry)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .glassRow(cornerRadius: 20, fillOpacity: 0.05, borderOpacity: 0.08)
    }

    private func historySubtitle(_ entry: HistoryEntry) -> String {
        var text = "\(ByteFormat.string(entry.sizeBytes)) · \(entry.senderName) · \(HomeView.timeString(entry.dateMs))"
        if let error = entry.errorMessage {
            text += " · \(error)"
        }
        return text
    }

    @ViewBuilder
    private func outcomeChip(_ entry: HistoryEntry) -> some View {
        switch entry.outcome {
        case "completed" where entry.direction == "outgoing":
            chip(text: "Sent", color: Theme.teal, systemImage: "arrow.up")
        case "completed":
            chip(text: entry.savedTo == "photos" ? "Photos" : "Files",
                 color: Theme.teal, systemImage: "checkmark")
        case "failed":
            chip(text: "Failed", color: Theme.danger, systemImage: "exclamationmark.triangle")
        case "rejected":
            chip(text: "Declined", color: Theme.textBase.opacity(0.4), systemImage: "hand.raised")
        case "cancelled":
            chip(text: "Cancelled", color: Theme.textBase.opacity(0.4), systemImage: "xmark")
        default:
            chip(text: entry.outcome.capitalized, color: Theme.textBase.opacity(0.4), systemImage: "circle")
        }
    }

    private func chip(text: String, color: Color, systemImage: String) -> some View {
        HStack(spacing: 5) {
            Image(systemName: systemImage)
                .font(.system(size: 9, weight: .bold))
            Text(text.uppercased())
                .font(Theme.mono(9.5, .medium))
                .tracking(0.5)
        }
        .foregroundColor(color)
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .background(Capsule().fill(color.opacity(0.12)))
    }

    // MARK: Photos (history → gallery)

    @ViewBuilder
    private var photosSection: some View {
        let entries = history.entries.filter { $0.outcome == "completed" && $0.savedTo == "photos" }
        if entries.isEmpty {
            emptyState(title: "Nothing in Photos yet",
                       message: "Media saved to your gallery will be listed here. Find the files themselves in the Photos app — look for the “Sendro” album.")
        } else {
            ForEach(entries) { entry in
                historyRow(entry)
            }
            footnote("These live in the Photos app — look for the “Sendro” album.")
        }
    }

    // MARK: Files (live store)

    @ViewBuilder
    private var filesSection: some View {
        if fileStore.files.isEmpty {
            emptyState(title: "No files yet",
                       message: "Received files that stay on this iPhone appear here. You can also browse them in the Files app under “On My iPhone › Sendro”.")
        } else {
            ForEach(fileStore.files) { file in
                fileRow(file)
            }
            footnote("Also available in the Files app: On My iPhone › Sendro.")
        }
    }

    private func fileRow(_ file: ReceivedFile) -> some View {
        HStack(spacing: 13) {
            FileBadge(fileName: file.name, side: 44, cornerRadius: 13)
            VStack(alignment: .leading, spacing: 3) {
                Text(file.name)
                    .font(Theme.sans(15, .medium))
                    .foregroundColor(Theme.textBase.opacity(0.93))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text("\(ByteFormat.string(file.sizeBytes)) · \(file.modified.formatted(date: .abbreviated, time: .shortened))")
                    .font(Theme.mono(10.5))
                    .foregroundColor(Theme.textTertiary)
                    .lineLimit(1)
            }
            Spacer()
            Menu {
                ShareLink(item: file.url) {
                    Label("Share / Open In…", systemImage: "square.and.arrow.up")
                }
                Button {
                    exportFile = file
                } label: {
                    Label("Save to Files…", systemImage: "folder.badge.plus")
                }
                Button(role: .destructive) {
                    deleteCandidate = file
                } label: {
                    Label("Delete", systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(Theme.textBase.opacity(0.55))
                    .frame(width: 32, height: 32)
                    .glassRow(cornerRadius: 16, fillOpacity: 0.06, borderOpacity: 0.1)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .glassRow(cornerRadius: 20, fillOpacity: 0.05, borderOpacity: 0.08)
    }

    // MARK: Empty + footnotes

    private func emptyState(title: String, message: String) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(Theme.sans(17, .semibold))
                .foregroundColor(Theme.textPrimary)
            Text(message)
                .font(Theme.sans(13))
                .foregroundColor(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .glassRow(cornerRadius: 22, fillOpacity: 0.04, borderOpacity: 0.07)
        .padding(.top, 8)
    }

    private func footnote(_ text: String) -> some View {
        Text(text)
            .font(Theme.sans(12))
            .foregroundColor(Theme.textFaint)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 4)
            .padding(.top, 6)
    }
}

// MARK: - Document picker (Save to Files)

struct DocumentExportPicker: UIViewControllerRepresentable {

    let url: URL

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        UIDocumentPickerViewController(forExporting: [url], asCopy: true)
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}
}
