//
//  FilesView.swift
//  Sendro
//
//  Received files kept in Documents/Sendro (also visible in the Files app).
//

import SwiftUI
import UIKit

struct FilesView: View {

    @EnvironmentObject private var fileStore: FileStore

    @State private var exportFile: ReceivedFile?
    @State private var deleteCandidate: ReceivedFile?

    var body: some View {
        NavigationStack {
            List {
                if fileStore.files.isEmpty {
                    Section {
                        VStack(spacing: 10) {
                            Image(systemName: "folder")
                                .font(.system(size: 36))
                                .foregroundColor(.secondary)
                            Text("No files yet")
                                .font(.headline)
                            Text("Received files that stay on this iPhone appear here. You can also browse them in the Files app under “On My iPhone › Sendro”.")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 24)
                    }
                    .listRowBackground(Color.clear)
                } else {
                    Section {
                        ForEach(fileStore.files) { file in
                            FileRow(file: file,
                                    onExport: { exportFile = file },
                                    onDelete: { deleteCandidate = file })
                        }
                    } footer: {
                        Text("Also available in the Files app: On My iPhone › Sendro.")
                    }
                }
            }
            .navigationTitle("Files")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        fileStore.refresh()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel("Refresh")
                }
            }
            .refreshable {
                fileStore.refresh()
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
        }
    }
}

// MARK: - Row

private struct FileRow: View {

    let file: ReceivedFile
    let onExport: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: FileIcon.symbol(forFileName: file.name))
                .font(.title3)
                .foregroundColor(.accentColor)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(file.name)
                    .font(.body.weight(.medium))
                    .lineLimit(2)
                Text("\(ByteFormat.string(file.sizeBytes)) · \(file.modified.formatted(date: .abbreviated, time: .shortened))")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Spacer()
            Menu {
                ShareLink(item: file.url) {
                    Label("Share / Open In…", systemImage: "square.and.arrow.up")
                }
                Button {
                    onExport()
                } label: {
                    Label("Save to Files…", systemImage: "folder.badge.plus")
                }
                Button(role: .destructive) {
                    onDelete()
                } label: {
                    Label("Delete", systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis.circle")
                    .font(.title3)
                    .foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 2)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) {
                onDelete()
            } label: {
                Label("Delete", systemImage: "trash")
            }
        }
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
