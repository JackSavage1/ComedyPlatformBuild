import SwiftUI
import UIKit

/// Spec 3.2: signed in, but this login isn't in `app_admins` (or the check
/// couldn't reach the server — then `failureMessage` is set). Shows the
/// email, the SQL the owner must run, Retry and Sign out.
@MainActor
struct NotAuthorizedView: View {
    let email: String
    /// nil = "not an admin"; non-nil = "couldn't check" with this message.
    var failureMessage: String?

    @Environment(AppStore.self) private var store
    @State private var didCopy = false

    init(email: String, failureMessage: String? = nil) {
        self.email = email
        self.failureMessage = failureMessage
    }

    /// The same statement as docs/MOBILE_SETUP.md / the migration's comment.
    private var sql: String {
        let escaped = email.replacingOccurrences(of: "'", with: "''")
        return """
        insert into app_admins (user_id)
        select id from auth.users where email = '\(escaped)';
        """
    }

    var body: some View {
        let auth = store.auth

        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    Image(systemName: failureMessage == nil ? "lock.shield" : "wifi.exclamationmark")
                        .font(.system(size: 44))
                        .foregroundStyle(Theme.accent)
                    Text(failureMessage == nil ? "Account not authorized" : "Couldn't check your access")
                        .font(.title2.weight(.bold))
                    Text(verbatim: "Signed in as \(email)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                if let failureMessage = failureMessage {
                    Text(verbatim: failureMessage)
                        .font(.body)
                    Text("Check your connection and tap Retry.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else {
                    Text("This login can read nothing and change nothing until the owner adds it to the app_admins table. In the Supabase dashboard open SQL Editor → New query, run this, then tap Retry:")
                        .font(.body)

                    VStack(alignment: .leading, spacing: 8) {
                        Text(verbatim: sql)
                            .font(.system(.footnote, design: .monospaced))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Button {
                            UIPasteboard.general.string = sql
                            didCopy = true
                        } label: {
                            Label(didCopy ? "Copied" : "Copy SQL", systemImage: didCopy ? "checkmark" : "doc.on.doc")
                                .font(.footnote.weight(.semibold))
                        }
                        .buttonStyle(.bordered)
                    }
                    .padding(12)
                    .background(Theme.cardBackground, in: RoundedRectangle(cornerRadius: 10, style: .continuous))

                    Text("Details: docs/MOBILE_SETUP.md")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                VStack(spacing: 12) {
                    Button {
                        Task { await store.checkAdmin() }
                    } label: {
                        HStack {
                            if auth.isCheckingAccess {
                                ProgressView()
                                    .tint(.white)
                            }
                            Text("Retry")
                                .font(.headline)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(auth.isCheckingAccess)

                    Button(role: .destructive) {
                        Task { await store.signOut() }
                    } label: {
                        Text("Sign out")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.bordered)
                }
            }
            .padding(24)
        }
    }
}
