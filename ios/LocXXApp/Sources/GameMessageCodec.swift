import Foundation
import LocXXRules

enum GameMessageCodec {
    static func decodeAppPayload(_ data: Data) throws -> [String: Any] {
        let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let root = obj, (root["v"] as? Int) == 1 else {
            throw NSError(domain: "GameMessageCodec", code: 1)
        }
        return root
    }

    static func encodeRoll(_ roll: DiceRoll, activePlayerIndex: Int, cid: Int) throws -> Data {
        let body: [String: Any] = [
            "white1": roll.white1,
            "white2": roll.white2,
            "red": roll.red,
            "yellow": roll.yellow,
            "green": roll.green,
            "blue": roll.blue,
            "activePlayerIndex": activePlayerIndex,
            "cid": cid,
        ]
        let root: [String: Any] = [
            "v": 1,
            "kind": "roll",
            "body": body,
        ]
        return try JSONSerialization.data(withJSONObject: root)
    }

    static func parseRoll(_ root: [String: Any]) throws -> (DiceRoll, Int) {
        guard let body = root["body"] as? [String: Any] else { throw NSError(domain: "GameMessageCodec", code: 2) }
        let roll = DiceRoll(
            white1: body["white1"] as! Int,
            white2: body["white2"] as! Int,
            red: body["red"] as! Int,
            yellow: body["yellow"] as! Int,
            green: body["green"] as! Int,
            blue: body["blue"] as! Int
        )
        let active = body["activePlayerIndex"] as! Int
        return (roll, active)
    }

    static func encodeGameState(_ state: MatchState) throws -> Data {
        var sheets: [[String: Any]] = []
        for sheet in state.playerSheets {
            var rows: [String: Any] = [:]
            for row in RowId.allCases {
                let pr = sheet.rows[row]!
                rows[row.name] = [
                    "last": pr.lastCrossedIndex,
                    "count": pr.crossCount,
                    "locked": pr.locked,
                ]
            }
            sheets.append([
                "rows": rows,
                "penalties": sheet.penalties,
            ])
        }
        let dice = state.diceInPlay.map(\.name)
        let locked = state.globallyLockedRows.map(\.name)
        let body: [String: Any] = [
            "playerCount": state.playerCount,
            "activePlayerIndex": state.activePlayerIndex,
            "sheets": sheets,
            "diceInPlay": dice,
            "globallyLockedRows": locked,
        ]
        let root: [String: Any] = [
            "v": 1,
            "kind": "game_state",
            "body": body,
        ]
        return try JSONSerialization.data(withJSONObject: root)
    }
}

extension RowId {
    var name: String {
        switch self {
        case .red: return "red"
        case .yellow: return "yellow"
        case .green: return "green"
        case .blue: return "blue"
        }
    }

    static func from(name: String) -> RowId? {
        switch name {
        case "red": return .red
        case "yellow": return .yellow
        case "green": return .green
        case "blue": return .blue
        default: return nil
        }
    }
}

extension DieColor {
    var name: String {
        switch self {
        case .white1: return "white1"
        case .white2: return "white2"
        case .red: return "red"
        case .yellow: return "yellow"
        case .green: return "green"
        case .blue: return "blue"
        }
    }

    static func from(name: String) -> DieColor? {
        switch name {
        case "white1": return .white1
        case "white2": return .white2
        case "red": return .red
        case "yellow": return .yellow
        case "green": return .green
        case "blue": return .blue
        default: return nil
        }
    }
}
