import ChessCore
import ChessTraining
import CoreGraphics
import Foundation
import ImageIO
import StoreKit
import UniformTypeIdentifiers
#if canImport(UIKit)
import UIKit
#endif

/// Result returned by the Brass Pawn chessboard vision endpoint (`gpt-6.1-terra`).
struct BoardVisionResult: Decodable, Sendable, Equatable {
    struct Quota: Decodable, Sendable, Equatable {
        let used: Int
        let limit: Int
        let remaining: Int
        let isPro: Bool
    }

    let fen: String
    let piecePlacement: String
    let turn: String
    let castling: String
    let orientation: String
    let isLegal: Bool
    let confidence: String
    let notes: String?
    let quota: Quota?

    var boardOrientation: PieceColor {
        orientation.lowercased() == "black" ? .black : .white
    }

    var sideToMove: PieceColor {
        turn.lowercased().hasPrefix("b") ? .black : .white
    }
}

enum BoardVisionError: Error, Equatable, Sendable {
    case invalidImage
    case freeLimitReached(used: Int, limit: Int)
    case dailyLimitReached(used: Int, limit: Int)
    case serviceBusy
    case recognitionFailed(String?)
    case networkUnavailable

    var message: String {
        switch self {
        case .invalidImage:
            L.t("photo.invalidImage", "Could not prepare that image for recognition.")
        case .freeLimitReached:
            L.t("photo.freeLimitReached", "Today's free AI board scan has been used. Upgrade to Brass Pawn Pro for up to 100 scans a day.")
        case .dailyLimitReached:
            L.t("photo.dailyLimitReached", "Daily limit of 100 AI board scans reached. Resets at 00:00 UTC.")
        case .serviceBusy:
            L.t("photo.serviceBusy", "Board recognition is busy right now. Please try again in a moment.")
        case .recognitionFailed:
            L.t("photo.recognitionFailed", "Could not read a chessboard from that image. Try a clearer angle or adjust the corners manually.")
        case .networkUnavailable:
            L.t("photo.networkError", "Could not reach the board scanner. Check your connection and try again.")
        }
    }
}

/// Sends a resized board photograph to the Brass Pawn vision service and turns
/// the recognized FEN into a playable or editable board state.
enum BoardVisionService {
    static let defaultEndpoint = URL(string: "https://wpaqi7buvesqegduuamwnwxqmq0mgjut.lambda-url.eu-central-1.on.aws/")!
    static let maxDimension: CGFloat = 1280
    static let jpegQuality: CGFloat = 0.82
    private static let installIDKey = "brasspawn.clientInstallID"

    static var endpoint: URL {
        #if DEBUG
        if let override = ProcessInfo.processInfo.environment["BRASSPAWN_VISION_URL"],
           let url = URL(string: override) {
            return url
        }
        #endif
        return defaultEndpoint
    }

    /// Persistent random per-install identifier used only for anonymous daily
    /// quota enforcement when no AppTransaction JWS is present.
    static func clientInstallID(defaults: UserDefaults = .standard) -> String {
        if let existing = defaults.string(forKey: installIDKey),
           !existing.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return existing
        }
        let created = UUID().uuidString.lowercased()
        defaults.set(created, forKey: installIDKey)
        return created
    }

    struct RequestPayload: Encodable, Sendable {
        let imageBase64: String
        let mimeType: String
        let entitlementJWS: String?
        let appTransactionJWS: String?
        let clientInstallID: String
    }

    private struct ErrorEnvelope: Decodable {
        let error: String?
        let code: String?
        let detail: String?
        let limit: Int?
        let used: Int?
        let isPro: Bool?
    }

    #if canImport(UIKit)
    /// Normalizes EXIF orientation, downscales to max 1280px, and encodes JPEG at 0.82 quality.
    static func prepareJPEGData(from image: UIImage) -> Data? {
        let sourceSize = image.size
        guard sourceSize.width > 1, sourceSize.height > 1 else { return nil }
        let longest = max(sourceSize.width, sourceSize.height)
        let scale = longest > maxDimension ? (maxDimension / longest) : 1.0
        let targetSize = CGSize(
            width: max(1, (sourceSize.width * scale).rounded()),
            height: max(1, (sourceSize.height * scale).rounded())
        )

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        let normalized = renderer.image { context in
            UIColor.black.setFill()
            context.fill(CGRect(origin: .zero, size: targetSize))
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
        return normalized.jpegData(compressionQuality: jpegQuality)
    }

    static func recognize(image: UIImage, session: URLSession = visionSession) async throws -> BoardVisionResult {
        guard let jpeg = prepareJPEGData(from: image) else {
            throw BoardVisionError.invalidImage
        }
        return try await recognize(jpegData: jpeg, session: session)
    }
    #endif

    /// CoreGraphics JPEG encoder used by cross-platform tests and non-UIKit hosts.
    static func prepareJPEGData(from cgImage: CGImage) -> Data? {
        let width = CGFloat(cgImage.width)
        let height = CGFloat(cgImage.height)
        guard width > 1, height > 1 else { return nil }
        let longest = max(width, height)
        let scale = longest > maxDimension ? (maxDimension / longest) : 1.0
        let targetW = max(1, Int((width * scale).rounded()))
        let targetH = max(1, Int((height * scale).rounded()))

        guard let ctx = CGContext(
            data: nil,
            width: targetW,
            height: targetH,
            bitsPerComponent: 8,
            bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: targetW, height: targetH))
        guard let scaled = ctx.makeImage() else { return nil }

        let data = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(
            data as CFMutableData,
            UTType.jpeg.identifier as CFString,
            1,
            nil
        ) else { return nil }
        CGImageDestinationAddImage(
            dest,
            scaled,
            [kCGImageDestinationLossyCompressionQuality: jpegQuality] as CFDictionary
        )
        guard CGImageDestinationFinalize(dest) else { return nil }
        return data as Data
    }

    static let visionSession: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieAcceptPolicy = .never
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 45
        configuration.timeoutIntervalForResource = 60
        return URLSession(configuration: configuration)
    }()

    /// Collects the active verified Brass Pawn Pro/Lifetime JWS and optional AppTransaction JWS.
    static func collectStoreKitJWS() async -> (entitlementJWS: String?, appTransactionJWS: String?) {
        var entitlementJWS: String?
        for await verification in Transaction.currentEntitlements {
            guard case .verified(let transaction) = verification,
                  SubscriptionStore.ProductID.all.contains(transaction.productID),
                  transaction.revocationDate == nil,
                  !transaction.isUpgraded
            else { continue }
            entitlementJWS = verification.jwsRepresentation
            if transaction.productID == SubscriptionStore.ProductID.lifetime {
                break
            }
        }
        let appTxJWS = (try? await AppTransaction.shared)?.jwsRepresentation
        return (entitlementJWS, appTxJWS)
    }

    static func recognize(jpegData: Data, session: URLSession = visionSession) async throws -> BoardVisionResult {
        let tokens = await collectStoreKitJWS()
        let payload = RequestPayload(
            imageBase64: jpegData.base64EncodedString(),
            mimeType: "image/jpeg",
            entitlementJWS: tokens.entitlementJWS,
            appTransactionJWS: tokens.appTransactionJWS,
            clientInstallID: clientInstallID()
        )

        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.timeoutInterval = 45
        request.httpBody = try JSONEncoder().encode(payload)

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw BoardVisionError.networkUnavailable
        }

        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0
        return try decodeResponse(data: data, statusCode: statusCode)
    }

    static func decodeResponse(data: Data, statusCode: Int) throws -> BoardVisionResult {
        if statusCode == 200 {
            do {
                return try JSONDecoder().decode(BoardVisionResult.self, from: data)
            } catch {
                throw BoardVisionError.recognitionFailed(nil)
            }
        }

        let envelope = try? JSONDecoder().decode(ErrorEnvelope.self, from: data)
        let code = envelope?.error ?? envelope?.code

        if statusCode == 429 {
            if code == "free_limit_reached" || envelope?.isPro == false {
                throw BoardVisionError.freeLimitReached(
                    used: envelope?.used ?? 1,
                    limit: envelope?.limit ?? 1
                )
            }
            if code == "daily_limit_reached" || envelope?.isPro == true {
                throw BoardVisionError.dailyLimitReached(
                    used: envelope?.used ?? 100,
                    limit: envelope?.limit ?? 100
                )
            }
            throw BoardVisionError.serviceBusy
        }

        if statusCode == 400 || statusCode == 413 || code == "invalid_image" || code == "payload_too_large" {
            throw BoardVisionError.invalidImage
        }

        throw BoardVisionError.recognitionFailed(envelope?.detail ?? code)
    }

    // MARK: - FEN extraction & side-to-move reconstruction

    struct ExtractedBoard: Equatable, Sendable {
        var pieces: [Square: Piece]
        var sideToMove: PieceColor
        var castling: CastlingRights

        var fen: String {
            var rows: [String] = []
            for rank in stride(from: 7, through: 0, by: -1) {
                var row = ""
                var empty = 0
                for file in 0..<8 {
                    if let piece = pieces[Square(file: file, rank: rank)] {
                        if empty > 0 { row += String(empty); empty = 0 }
                        row.append(piece.character)
                    } else {
                        empty += 1
                    }
                }
                if empty > 0 { row += String(empty) }
                rows.append(row)
            }
            var rights = ""
            if castling.contains(.whiteKingside) { rights += "K" }
            if castling.contains(.whiteQueenside) { rights += "Q" }
            if castling.contains(.blackKingside) { rights += "k" }
            if castling.contains(.blackQueenside) { rights += "q" }
            return "\(rows.joined(separator: "/")) \(sideToMove == .white ? "w" : "b") \(rights.isEmpty ? "-" : rights) - 0 1"
        }

        var position: Position? {
            Position(fen: fen)
        }
    }

    /// Parses piece placement, side to move, and castling rights from a FEN string
    /// even when `Position(fen:)` would refuse an incomplete or illegal board.
    static func extractBoard(from fen: String) -> ExtractedBoard {
        let fields = fen.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: " ").map(String.init)
        let placement = fields.first ?? ""
        let turnField = fields.count > 1 ? fields[1] : "w"
        let castlingField = fields.count > 2 ? fields[2] : "-"

        var pieces: [Square: Piece] = [:]
        let ranks = placement.split(separator: "/", omittingEmptySubsequences: false)
        for (rowIndex, rankText) in ranks.prefix(8).enumerated() {
            let rank = 7 - rowIndex
            var file = 0
            for ch in rankText {
                guard file < 8 else { break }
                if let skip = ch.wholeNumberValue {
                    file += skip
                } else if let piece = Piece(character: ch) {
                    pieces[Square(file: file, rank: rank)] = piece
                    file += 1
                }
            }
        }

        var rights: CastlingRights = []
        if castlingField.contains("K") { rights.insert(.whiteKingside) }
        if castlingField.contains("Q") { rights.insert(.whiteQueenside) }
        if castlingField.contains("k") { rights.insert(.blackKingside) }
        if castlingField.contains("q") { rights.insert(.blackQueenside) }

        return ExtractedBoard(
            pieces: pieces,
            sideToMove: turnField.lowercased().hasPrefix("b") ? .black : .white,
            castling: rights
        )
    }
}
