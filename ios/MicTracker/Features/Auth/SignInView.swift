import SwiftUI

/// Spec 6.1: app name + 🎤, email, password, Sign in, error text.
/// There is no sign-up here — the owner creates the account in the Supabase
/// dashboard (docs/MOBILE_SETUP.md). "Try the demo" (spec 8.1) opens the app
/// with sample data instead.
@MainActor
struct SignInView: View {
    @Environment(AppStore.self) private var store

    @State private var email = ""
    @State private var password = ""
    @FocusState private var focusedField: Field?

    private enum Field: Hashable {
        case email
        case password
    }

    var body: some View {
        let auth = store.auth

        ScrollView {
            VStack(spacing: 24) {
                VStack(spacing: 8) {
                    Text(verbatim: "🎤")
                        .font(.system(size: 64))
                    Text("Mic Tracker")
                        .font(.largeTitle.weight(.bold))
                    Text("NYC open mics, your sets, your stats.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 48)

                VStack(spacing: 12) {
                    TextField("Email", text: $email)
                        .textContentType(.username)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                        .submitLabel(.next)
                        .focused($focusedField, equals: .email)
                        .onSubmit { focusedField = .password }
                        .padding(12)
                        .background(Theme.cardBackground, in: RoundedRectangle(cornerRadius: 10, style: .continuous))

                    SecureField("Password", text: $password)
                        .textContentType(.password)
                        .submitLabel(.go)
                        .focused($focusedField, equals: .password)
                        .onSubmit(submit)
                        .padding(12)
                        .background(Theme.cardBackground, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                }

                if let error = auth.signInError {
                    Text(verbatim: error)
                        .font(.footnote)
                        .foregroundStyle(Theme.negative)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                }

                Button(action: submit) {
                    HStack {
                        if auth.isSigningIn {
                            ProgressView()
                                .tint(.white)
                        }
                        Text(auth.isSigningIn ? "Signing in…" : "Sign in")
                            .font(.headline)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .disabled(auth.isSigningIn || email.isEmpty || password.isEmpty)

                TryDemoButton()

                Text("Accounts are created by the owner in the Supabase dashboard — see docs/MOBILE_SETUP.md.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 24)
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    private func submit() {
        guard !store.auth.isSigningIn else { return }
        focusedField = nil
        let enteredEmail = email
        let enteredPassword = password
        Task {
            await store.signIn(email: enteredEmail, password: enteredPassword)
        }
    }
}
