import CloudDriverSwift
import SwiftUI

private let activityDateFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.dateStyle = .medium
    formatter.timeStyle = .short
    return formatter
}()

/// A human-readable label for one `AuditEvent`'s `action` name - the same enum names the server
/// records (`FILE_UPLOAD`, `FOLDER_DELETE`, ...), rendered as plain title-case words.
private func activityActionLabel(_ action: String) -> String {
    action.split(separator: "_").map { $0.capitalized }.joined(separator: " ")
}

/// One row of an activity feed, shared by the account-wide feed and a per-file scoped one.
struct ActivityRow: View {
    let entry: ActivityEntryResponse
    let showDivider: Bool

    var body: some View {
        CloudRow(
            icon: "list.bullet.rectangle",
            iconColor: CloudTheme.accent,
            title: activityActionLabel(entry.action),
            subtitle: activityDateFormatter.string(from: Date(timeIntervalSince1970: Double(entry.timestampEpochMillis) / 1000)),
            showDivider: showDivider
        ) { EmptyView() }
    }
}

/// The shared list body both the account-wide feed and a per-file scoped sheet render - a
/// `CloudCard` of `ActivityRow`s plus a "Load more" row, matching this app's existing
/// explicit-tap-not-auto-scroll pagination convention (`FileBrowserView`'s own "Load more").
struct ActivityListContent: View {
    let entries: [ActivityEntryResponse]
    let hasMore: Bool
    let isLoading: Bool
    let isAvailable: Bool
    let onLoadMore: () -> Void

    var body: some View {
        if !isAvailable {
            VStack(spacing: 8) {
                Image(systemName: "list.bullet.rectangle")
                    .font(.system(size: 32))
                    .foregroundStyle(CloudTheme.textSecondary)
                Text("Activity history isn't available on this server")
                    .foregroundStyle(CloudTheme.textSecondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 40)
        } else if isLoading && entries.isEmpty {
            ProgressView().tint(.white).frame(maxWidth: .infinity).padding(.top, 40)
        } else if entries.isEmpty {
            Text("No activity yet.")
                .foregroundStyle(CloudTheme.textSecondary)
                .frame(maxWidth: .infinity)
                .padding(.top, 40)
        } else {
            CloudCard(icon: "list.bullet.rectangle", iconColor: CloudTheme.accent, title: "Activity") {
                VStack(spacing: 0) {
                    ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                        ActivityRow(entry: entry, showDivider: index != entries.count - 1 || hasMore)
                    }
                    if hasMore {
                        Button {
                            onLoadMore()
                        } label: {
                            if isLoading {
                                ProgressView()
                            } else {
                                Text("Load more")
                            }
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(CloudTheme.accent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 11)
                        .disabled(isLoading)
                    }
                }
            }
        }
    }
}

/// The account-wide feed, reached via Dashboard's "Recent Activity" section "View All" link -
/// cursor-paginated, "Load more" on tap.
struct ActivityFeedView: View {
    @ObservedObject var viewModel: AppViewModel

    var body: some View {
        ZStack {
            CloudTheme.backgroundGradient
            ScrollView {
                ActivityListContent(
                    entries: viewModel.accountActivity,
                    hasMore: viewModel.accountActivityHasMore,
                    isLoading: viewModel.busy,
                    isAvailable: viewModel.isAccountActivityAvailable,
                    onLoadMore: { viewModel.loadMoreActivity() }
                )
                .padding(16)
            }
            .scrollIndicators(.hidden)
        }
        .navigationTitle("Activity")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .task {
            viewModel.loadFullActivity()
        }
    }
}

/// A per-file scoped feed, reached via a file row's "Activity" quick action - presented as a sheet
/// (the same modal shape `VersionHistorySheet` uses), not a navigation push.
struct FileActivitySheet: View {
    @ObservedObject var viewModel: AppViewModel
    let file: StoredFileSummaryResponse
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ZStack {
                CloudTheme.backgroundGradient
                ScrollView {
                    ActivityListContent(
                        entries: viewModel.fileActivity,
                        hasMore: viewModel.fileActivityHasMore,
                        isLoading: viewModel.busy,
                        isAvailable: viewModel.isFileActivityAvailable,
                        onLoadMore: { viewModel.loadMoreFileActivity() }
                    )
                    .padding(16)
                }
                .scrollIndicators(.hidden)
            }
            .navigationTitle(file.fileName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
