import CloudDriverSwift
import SwiftUI
import UIKit

/// A batch of files/folders being shared (owner side) via `ShareSheet` - wraps `[SelectableEntry]`
/// so it can drive a SwiftUI `.sheet(item:)`; a single-item share (from a row's own "..." menu) is
/// just a one-element `entries` array, which keeps that case's existing "who is this shared with,
/// with revoke" behavior (see `ShareSheet.loadShares`/`revoke` below - only meaningful for exactly
/// one item).
struct ShareTargets: Identifiable {
    let entries: [SelectableEntry]
    var id: String { entries.map(\.id).joined(separator: ",") }

    var displayName: String {
        entries.count == 1 ? entries[0].displayName : "\(entries.count) items"
    }
}

/// Fixed expiry choices for both a grant and a public link - a full date/time picker would be
/// more flexible but adds friction for no real benefit at this app's scale; these four presets
/// cover the common cases with one tap.
enum SharingExpiryPreset: String, CaseIterable, Identifiable {
    case never = "Never"
    case oneDay = "1 day"
    case sevenDays = "7 days"
    case thirtyDays = "30 days"

    var id: String { rawValue }

    var epochMillis: Int64? {
        let seconds: TimeInterval
        switch self {
        case .never: return nil
        case .oneDay: seconds = 86_400
        case .sevenDays: seconds = 7 * 86_400
        case .thirtyDays: seconds = 30 * 86_400
        }
        return Int64(Date().addingTimeInterval(seconds).timeIntervalSince1970 * 1000)
    }
}

/// Owner-side sharing: grant another account's access (view-only or, since the grantee's own
/// direct-file edit permission was added, editable) to one or more files/folders at once, with an
/// optional expiry, and - only when exactly one item is targeted, since a revoke list has no
/// single meaning across multiple items with potentially different grantee sets - see who it's
/// currently shared with and revoke that access. A single targeted *file* also offers public,
/// unauthenticated links (folders have no public-link route server-side). Fully self-contained
/// (its own local loading/error/list state) rather than routed through `AppViewModel`'s global
/// `busy` guard - the same reasoning cloud-driver-platforms-desktop's own `ShareDialog` documents:
/// tying a modal's own actions to a screen-wide busy flag would disable the rest of the app for no
/// reason while it's simply open.
///
/// Granting `EDIT` only ever affects a direct grant on a single file - there is no in-app "now
/// edit this shared file's content" flow anywhere in this app (no in-app editor exists at all), so
/// `EDIT` is offered purely as a grant a grantee's own separate tooling could make use of.
struct ShareSheet: View {
    @ObservedObject var viewModel: AppViewModel
    let targets: ShareTargets
    @Environment(\.dismiss) private var dismiss

    @State private var email = ""
    @State private var permissionLevel = "VIEW"
    @State private var expiryPreset: SharingExpiryPreset = .never
    @State private var grantees: [String] = []
    @State private var isLoading = false
    @State private var isSubmitting = false
    @State private var errorMessage: String?

    @State private var publicLinks: [PublicFileLinkSummaryResponse] = []
    @State private var isCreatingPublicLink = false
    @State private var copiedToken: String?

    var body: some View {
        NavigationStack {
            ZStack {
                CloudTheme.backgroundGradient

                ScrollView {
                    VStack(spacing: 16) {
                        CloudCard(
                            icon: "person.badge.plus",
                            iconColor: CloudTheme.iconAccount,
                            title: "Share",
                            subtitle: targets.displayName
                        ) {
                            VStack(spacing: 12) {
                                HStack(spacing: 10) {
                                    GlassField {
                                        TextField("Email address", text: $email)
                                            .textContentType(.emailAddress)
                                            .keyboardType(.emailAddress)
                                            .textInputAutocapitalization(.never)
                                            .autocorrectionDisabled()
                                    }
                                    Button {
                                        share()
                                    } label: {
                                        if isSubmitting {
                                            ProgressView().tint(.white)
                                        } else {
                                            Image(systemName: "paperplane.fill")
                                        }
                                    }
                                    .frame(width: 46, height: 46)
                                    .foregroundStyle(.white)
                                    .background(CloudTheme.accent.gradient, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                    .opacity(email.trimmingCharacters(in: .whitespaces).isEmpty || isSubmitting ? 0.5 : 1)
                                    .disabled(email.trimmingCharacters(in: .whitespaces).isEmpty || isSubmitting)
                                }

                                Picker("Permission", selection: $permissionLevel) {
                                    Text("View").tag("VIEW")
                                    Text("Edit").tag("EDIT")
                                }
                                .pickerStyle(.segmented)

                                Picker("Expires", selection: $expiryPreset) {
                                    ForEach(SharingExpiryPreset.allCases) { preset in
                                        Text(preset.rawValue).tag(preset)
                                    }
                                }
                                .pickerStyle(.menu)
                                .tint(CloudTheme.textSecondary)
                            }
                            .padding(.horizontal, 16)
                            .padding(.bottom, 16)
                        }

                        // A revoke list has no single meaning across multiple items with
                        // potentially different grantee sets - only shown for a single target.
                        if targets.entries.count == 1 {
                            CloudCard(
                                icon: "person.2.fill",
                                iconColor: CloudTheme.iconStorage,
                                title: "Shared With",
                                subtitle: grantees.isEmpty ? "No one yet" : "\(grantees.count) \(grantees.count == 1 ? "person" : "people")"
                            ) {
                                VStack(spacing: 0) {
                                    ForEach(grantees.indices, id: \.self) { index in
                                        let granteeEmail = grantees[index]
                                        CloudRow(
                                            icon: "person.fill",
                                            iconColor: CloudTheme.iconAccount,
                                            title: granteeEmail,
                                            showDivider: index != grantees.count - 1
                                        ) {
                                            Button {
                                                revoke(granteeEmail)
                                            } label: {
                                                Image(systemName: "xmark.circle.fill")
                                                    .foregroundStyle(Color.red.opacity(0.85))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if let file = targetFile {
                            CloudCard(
                                icon: "link",
                                iconColor: CloudTheme.accent,
                                title: "Public Link",
                                subtitle: publicLinks.isEmpty ? "No active links" : "\(publicLinks.count) active"
                            ) {
                                VStack(spacing: 0) {
                                    ForEach(publicLinks) { link in
                                        CloudRow(
                                            icon: "link",
                                            iconColor: CloudTheme.accent,
                                            title: viewModel.publicLinkURL(token: link.token),
                                            subtitle: link.expiresAtEpochMillis == nil ? "Never expires" : "Expires \(Self.dateFormatter.string(from: Date(timeIntervalSince1970: Double(link.expiresAtEpochMillis!) / 1000)))",
                                            showDivider: link.token != publicLinks.last?.token
                                        ) {
                                            HStack(spacing: 12) {
                                                Button {
                                                    UIPasteboard.general.string = viewModel.publicLinkURL(token: link.token)
                                                    copiedToken = link.token
                                                } label: {
                                                    Image(systemName: copiedToken == link.token ? "checkmark" : "doc.on.doc")
                                                        .foregroundStyle(CloudTheme.accent)
                                                }
                                                Button {
                                                    revokePublicLink(link.token)
                                                } label: {
                                                    Image(systemName: "xmark.circle.fill")
                                                        .foregroundStyle(Color.red.opacity(0.85))
                                                }
                                            }
                                        }
                                    }
                                    Button {
                                        createPublicLink(for: file)
                                    } label: {
                                        HStack {
                                            if isCreatingPublicLink {
                                                ProgressView().tint(.white)
                                            } else {
                                                Image(systemName: "plus.circle.fill")
                                            }
                                            Text("Create link (\(expiryPreset.rawValue))")
                                        }
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 11)
                                    }
                                    .foregroundStyle(CloudTheme.accent)
                                    .disabled(isCreatingPublicLink)
                                }
                            }
                        }
                    }
                    .padding(16)
                }
                .scrollIndicators(.hidden)

                if isLoading && grantees.isEmpty {
                    ProgressView()
                        .tint(.white)
                }
            }
            .navigationTitle("Share")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task {
                await loadShares()
                await loadPublicLinks()
            }
            .alert("Error", isPresented: Binding(
                get: { errorMessage != nil },
                set: { isPresented in if !isPresented { errorMessage = nil } }
            )) {
                Button("OK", role: .cancel) { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        return formatter
    }()

    /// The one targeted file, if the whole batch is a single file - public links only ever apply
    /// to files, never folders, since there is no server-side folder public-link route.
    private var targetFile: StoredFileSummaryResponse? {
        guard targets.entries.count == 1, case .file(let file) = targets.entries[0] else { return nil }
        return file
    }

    /// Only meaningful for a single target - see the "Shared With" card's own `targets.entries.count == 1` guard.
    private func loadShares() async {
        guard targets.entries.count == 1 else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            switch targets.entries[0] {
            case .file(let file):
                grantees = try await viewModel.client.listFileShares(fileId: file.fileId)
            case .folder(let folder):
                grantees = try await viewModel.client.listFolderShares(folderId: folder.folderId)
            }
        } catch let error as APIError {
            errorMessage = error.errorDescription
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func loadPublicLinks() async {
        guard let file = targetFile else { return }
        do {
            publicLinks = try await viewModel.client.listPublicFileLinks(fileId: file.fileId)
        } catch {
            // Best-effort - an empty "Public Link" card with no links is a fine fallback state.
        }
    }

    private func share() {
        let trimmed = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        isSubmitting = true
        Task {
            defer { isSubmitting = false }
            do {
                try await viewModel.shareEntries(targets.entries, granteeEmail: trimmed, permissionLevel: permissionLevel, expiresAtEpochMillis: expiryPreset.epochMillis)
                email = ""
                await loadShares()
            } catch let error as APIError {
                errorMessage = error.errorDescription
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func revoke(_ granteeEmail: String) {
        guard targets.entries.count == 1 else { return }
        Task {
            do {
                switch targets.entries[0] {
                case .file(let file):
                    try await viewModel.client.revokeFileShare(fileId: file.fileId, granteeEmail: granteeEmail)
                case .folder(let folder):
                    try await viewModel.client.revokeFolderShare(folderId: folder.folderId, granteeEmail: granteeEmail)
                }
                await loadShares()
            } catch let error as APIError {
                errorMessage = error.errorDescription
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func createPublicLink(for file: StoredFileSummaryResponse) {
        isCreatingPublicLink = true
        Task {
            defer { isCreatingPublicLink = false }
            do {
                _ = try await viewModel.client.createPublicFileLink(fileId: file.fileId, expiresAtEpochMillis: expiryPreset.epochMillis)
                await loadPublicLinks()
            } catch let error as APIError {
                errorMessage = error.errorDescription
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func revokePublicLink(_ token: String) {
        guard let file = targetFile else { return }
        Task {
            do {
                try await viewModel.client.revokePublicFileLink(fileId: file.fileId, token: token)
                await loadPublicLinks()
            } catch let error as APIError {
                errorMessage = error.errorDescription
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}
