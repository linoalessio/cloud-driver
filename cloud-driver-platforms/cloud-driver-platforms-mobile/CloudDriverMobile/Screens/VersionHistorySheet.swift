import CloudDriverSwift
import SwiftUI

/// Lists every captured version of one file's content, newest first, with "Download" and
/// "Restore" actions on each - reached via a file row's "Version history" quick action. Stays
/// open across multiple actions rather than a one-shot modal, the same shape `ShareSheet` uses.
struct VersionHistorySheet: View {
    @ObservedObject var viewModel: AppViewModel
    let file: StoredFileSummaryResponse
    @Environment(\.dismiss) private var dismiss
    @State private var restoringVersion: Int?

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter
    }()

    var body: some View {
        NavigationStack {
            ZStack {
                CloudTheme.backgroundGradient

                if !viewModel.isVersionHistoryAvailable {
                    VStack(spacing: 8) {
                        Image(systemName: "clock.arrow.circlepath")
                            .font(.system(size: 32))
                            .foregroundStyle(CloudTheme.textSecondary)
                        Text("Version history isn't available on this server")
                            .foregroundStyle(CloudTheme.textSecondary)
                    }
                } else if viewModel.busy && viewModel.fileVersions.isEmpty {
                    ProgressView().tint(.white)
                } else {
                    ScrollView {
                        CloudCard(
                            icon: "clock.arrow.circlepath",
                            iconColor: CloudTheme.accent,
                            title: "Versions",
                            subtitle: file.fileName
                        ) {
                            if viewModel.fileVersions.isEmpty {
                                Text("No prior versions - this file has never been overwritten.")
                                    .foregroundStyle(CloudTheme.textSecondary)
                                    .padding(16)
                            } else {
                                VStack(spacing: 0) {
                                    ForEach(Array(viewModel.fileVersions.enumerated()), id: \.element.id) { index, version in
                                        CloudRow(
                                            icon: "doc.text",
                                            iconColor: CloudTheme.iconFile,
                                            title: "Version \(version.versionNumber)",
                                            subtitle: "\(Self.dateFormatter.string(from: Date(timeIntervalSince1970: Double(version.capturedAtEpochMillis) / 1000))) - \(formatBytes(version.sizeBytes))",
                                            showDivider: index != viewModel.fileVersions.count - 1
                                        ) {
                                            HStack(spacing: 16) {
                                                Button {
                                                    viewModel.downloadFileVersion(file, versionNumber: version.versionNumber)
                                                } label: {
                                                    Image(systemName: "arrow.down.circle")
                                                }
                                                Button {
                                                    restoringVersion = version.versionNumber
                                                } label: {
                                                    Image(systemName: "arrow.uturn.backward.circle")
                                                }
                                            }
                                            .foregroundStyle(CloudTheme.accent)
                                        }
                                    }
                                }
                            }
                        }
                        .padding(16)
                    }
                    .scrollIndicators(.hidden)
                }
            }
            .navigationTitle("Version History")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task {
                viewModel.loadFileVersions(file)
            }
            .alert("Restore this version?", isPresented: Binding(
                get: { restoringVersion != nil },
                set: { isPresented in if !isPresented { restoringVersion = nil } }
            )) {
                Button("Restore", role: .destructive) {
                    if let version = restoringVersion {
                        viewModel.restoreFileVersion(file, versionNumber: version)
                    }
                    restoringVersion = nil
                }
                Button("Cancel", role: .cancel) { restoringVersion = nil }
            } message: {
                Text("The file's current content is captured as a new version first, so this can be undone by restoring again.")
            }
        }
    }
}
