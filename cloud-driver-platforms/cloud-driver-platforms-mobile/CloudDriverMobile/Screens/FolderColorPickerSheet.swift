import SwiftUI

/// The row action's "Set color" sheet (added 2026-09-05) - a small grid of preset swatches (see
/// `FolderColorOption` in Theme.swift); tapping one immediately applies it via `onSelect` and the
/// caller dismisses the sheet, mirroring cloud-driver-platforms-desktop's own
/// `FolderColorPickerDialog` (no separate "confirm" step, since picking a color is a single,
/// easily-undoable action - just pick another color).
struct FolderColorPickerSheet: View {
    let currentColor: FolderColorOption
    let onSelect: (FolderColorOption) -> Void
    @Environment(\.dismiss) private var dismiss

    private let columns = [GridItem(.adaptive(minimum: 44), spacing: 16)]

    var body: some View {
        NavigationStack {
            ZStack {
                CloudTheme.backgroundGradient
                LazyVGrid(columns: columns, spacing: 16) {
                    ForEach(FolderColorOption.allCases) { option in
                        Button {
                            onSelect(option)
                        } label: {
                            ZStack {
                                Circle()
                                    .fill(option.color)
                                    .frame(width: 40, height: 40)
                                if option == currentColor {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(.white)
                                        .font(.system(size: 16, weight: .bold))
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(24)
            }
            .navigationTitle("Set Folder Color")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
    }
}
