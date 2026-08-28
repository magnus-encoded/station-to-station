import SwiftUI

// --- The palette, per file as everywhere else in this package ---
private let ground = Color(red: 0x0E / 255, green: 0x0B / 255, blue: 0x14 / 255)
private let ink = Color(red: 0xED / 255, green: 0xE9 / 255, blue: 0xF2 / 255)
private let muted = Color(red: 0x8B / 255, green: 0x82 / 255, blue: 0x99 / 255)
private let amber = Color(red: 0xE7 / 255, green: 0xB2 / 255, blue: 0x4C / 255)
private let spotifyGreen = Color(red: 0x1D / 255, green: 0xB9 / 255, blue: 0x54 / 255)

/// The first-run door. Twin of Android's `SplashScreen`.
///
/// It exists because the timeline never mentions that a playlist is possible, so the
/// only route to Spotify was a settings screen a first-time user has no reason to
/// open. One sentence about what the app is for, and two ways in.
///
/// **Skipping is a first-class answer, not a dodge.** Most of what this app does needs
/// no account at all (#225), and a door that only opens one way would be a paywall
/// wearing a welcome.
struct SplashView: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        ZStack {
            ground.ignoresSafeArea()
            VStack(spacing: 0) {
                Text("◦").font(.system(size: 20)).foregroundStyle(amber)
                Spacer().frame(height: 10)
                Text("Station to Station")
                    .font(.system(size: 30, design: .serif)).foregroundStyle(ink)
                Spacer().frame(height: 12)
                Text("Your concerts, kept. Connect Spotify to turn any night's "
                     + "setlist into a playlist — or skip and just browse the setlists.")
                    .font(.system(size: 14)).foregroundStyle(muted)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 4)
                Spacer().frame(height: 36)
                Button {
                    // Ordered this way on purpose: the login opens a sheet, and the
                    // door is counted as passed either way — a network that is down is
                    // not a reason to be asked the same question twice.
                    model.loginSpotify()
                    model.markOnboarded()
                } label: {
                    Text("Log in with Spotify").font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity).padding(.vertical, 12)
                        .background(spotifyGreen, in: RoundedRectangle(cornerRadius: 22))
                }
                Spacer().frame(height: 4)
                Button { model.markOnboarded() } label: {
                    Text("Skip — just show me setlists")
                        .font(.system(size: 15)).foregroundStyle(muted)
                        .frame(maxWidth: .infinity).padding(.vertical, 12)
                }
            }
            .padding(32)
        }
    }
}
