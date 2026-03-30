import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var model: LocXXViewModel
    @State private var name = "Player"

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("LocXX")
                    .font(.largeTitle)
                TextField("Display name", text: $name)
                    .multilineTextAlignment(.center)
                    .textFieldStyle(.roundedBorder)
                Button("Host game") { model.startHost(displayName: name) }
                Button("Join (LAN)") { model.startClient(displayName: name) }
                Button("Stop", role: .destructive) { model.stopAll() }
                if model.role == .host {
                    Text("Waiting for players on the same Wi‑Fi — they can tap Join (automatic).")
                        .font(.body)
                    Button("Roll dice (host)") { model.hostRollDice() }
                }
                if let r = model.lastRoll {
                    Text("Last roll: W1=\(r.white1) W2=\(r.white2) sum=\(r.whiteSum())")
                }
                if let m = model.match {
                    Text("Players: \(m.playerCount) active=\(m.activePlayerIndex) locks=\(m.globallyLockedRows.count)")
                }
                Text("Peers: \(model.peers.map { "\($0.displayName)#\($0.playerId)" }.joined(separator: ", "))")
                Divider()
                Text("Log").font(.headline)
                Text(model.log.joined(separator: "\n"))
                    .font(.caption)
            }
            .padding()
        }
    }
}
