import SwiftUI

// =============================================================================
// ChangePasswordView — Manage → Account → Change password (spec 9).
//
// ManageView presents it in a sheet (only for a real sign-in; the row is
// hidden in demo mode). Unlike ManageView, it makes its own NavigationStack
// for the title and the Cancel / Save buttons.
//
// Saving:
//   1. Save is enabled once all three fields have something in them.
//   2. PasswordRules.validationError runs first — no network call until the
//      form passes (too short → don't match → same as current).
//   3. AppStore.changePassword → AuthService: sign in again with the current
//      password, then PUT /auth/v1/user. A failure shows under the fields.
//   4. Success: `onChanged()`, then the sheet closes and ManageView shows
//      "Password changed.". The user stays signed in.
//
// The passwords only live in this view's @State: never logged, never saved,
// and wiped when the sheet closes.
// =============================================================================
@MainActor
struct ChangePasswordView: View {
    /// Called once the password really changed, just before the sheet closes.
    private let onChanged: () -> Void

    @Environment(AppStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    @State private var currentPassword = ""
    @State private var newPassword = ""
    @State private var confirmPassword = ""
    /// Validation or server message shown under the fields.
    @State private var errorMessage: String?
    @State private var isSaving = false
    @FocusState private var focusedField: Field?

    fileprivate enum Field: Hashable {
        case current
        case new
        case confirm
    }

    init(onChanged: @escaping () -> Void = {}) {
        self.onChanged = onChanged
    }

    /// Spec 9: Save only when every field is filled in (and not mid-save,
    /// which also blocks double taps).
    private var canSave: Bool {
        !isSaving && PasswordRules.canSubmit(current: currentPassword, new: newPassword, confirm: confirmPassword)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    PasswordField(
                        title: "Current password",
                        text: $currentPassword,
                        contentType: .password,
                        focus: $focusedField,
                        field: .current
                    )
                    .submitLabel(.next)
                    .onSubmit { focusedField = .new }

                    // `.newPassword` lets iCloud Keychain offer to save the
                    // new password (and suggest a strong one).
                    PasswordField(
                        title: "New password",
                        text: $newPassword,
                        contentType: .newPassword,
                        focus: $focusedField,
                        field: .new
                    )
                    .submitLabel(.next)
                    .onSubmit { focusedField = .confirm }

                    PasswordField(
                        title: "Confirm new password",
                        text: $confirmPassword,
                        contentType: .newPassword,
                        focus: $focusedField,
                        field: .confirm
                    )
                    .submitLabel(.done)
                    .onSubmit(save)
                } footer: {
                    Text(verbatim: PasswordRules.footnote)
                }

                if let errorMessage = errorMessage {
                    Section {
                        Text(verbatim: errorMessage)
                            .font(.footnote)
                            .foregroundStyle(Theme.negative)
                    }
                }
            }
            .disabled(isSaving)
            .navigationTitle("Change password")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .disabled(isSaving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isSaving {
                        ProgressView()
                    } else {
                        Button("Save", action: save)
                            .disabled(!canSave)
                    }
                }
            }
        }
        // No swipe-to-close while the request is running.
        .interactiveDismissDisabled(isSaving)
        .onDisappear(perform: clearFields)
    }

    // MARK: - Actions

    private func save() {
        guard canSave else { return }
        focusedField = nil

        // Spec 9 validation, before any network call.
        if let problem = PasswordRules.validationError(
            current: currentPassword,
            new: newPassword,
            confirm: confirmPassword
        ) {
            errorMessage = problem
            return
        }

        errorMessage = nil
        isSaving = true
        // Copy what the request needs: the view may go away before it ends.
        let appStore = store
        let current = currentPassword
        let replacement = newPassword
        Task {
            do {
                try await appStore.changePassword(current: current, new: replacement)
                isSaving = false
                clearFields()
                onChanged()
                dismiss()
            } catch {
                isSaving = false
                // A cancelled request (the sheet went away) isn't an error.
                guard !APIError.isCancellation(error) else { return }
                errorMessage = APIError.describe(error)
            }
        }
    }

    /// Spec 9: forget all three passwords when the screen closes.
    private func clearFields() {
        currentPassword = ""
        newPassword = ""
        confirmPassword = ""
    }
}

// MARK: - PasswordField

/// A password field with an eye button that shows or hides what's typed.
/// No autocorrect or autocapitalization, whichever way it's showing.
@MainActor
private struct PasswordField: View {
    let title: LocalizedStringKey
    @Binding var text: String
    let contentType: UITextContentType
    let focus: FocusState<ChangePasswordView.Field?>.Binding
    let field: ChangePasswordView.Field

    @State private var isRevealed = false

    var body: some View {
        HStack(spacing: 8) {
            // Swap between a SecureField (dots) and a plain TextField. The
            // modifiers on the Group apply to whichever one is showing.
            Group {
                if isRevealed {
                    TextField(title, text: $text)
                } else {
                    SecureField(title, text: $text)
                }
            }
            .textContentType(contentType)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled(true)
            .focused(focus, equals: field)

            Button {
                isRevealed.toggle()
            } label: {
                Image(systemName: isRevealed ? "eye.slash" : "eye")
                    .foregroundStyle(.secondary)
            }
            // Borderless, so tapping elsewhere in the row doesn't toggle it.
            .buttonStyle(.borderless)
            .accessibilityLabel(isRevealed ? "Hide password" : "Show password")
        }
    }
}
