import ChessCore
import CoreGraphics
import Foundation
import ImageIO
import Testing
@testable import BrassPawnApp

@Suite("AI Board Vision Service")
struct BoardVisionTests {
    @Test("resizes oversized images to max 1280px JPEG")
    func resizesLargeImageToMax1280() throws {
        let ctx = CGContext(
            data: nil, width: 2400, height: 1800, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )!
        ctx.setFillColor(CGColor(red: 0.2, green: 0.2, blue: 0.2, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: 2400, height: 1800))
        let large = ctx.makeImage()!

        let jpeg = try #require(BoardVisionService.prepareJPEGData(from: large))
        let src = try #require(CGImageSourceCreateWithData(jpeg as CFData, nil))
        let decoded = try #require(CGImageSourceCreateImageAtIndex(src, 0, nil))
        #expect(decoded.width == 1280)
        #expect(decoded.height == 960)
    }

    @Test("decodes 200 vision payload and 429 quota limits")
    func decodesVisionResponsesAndQuotas() throws {
        let okJSON = Data("""
        {
          "fen": "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3",
          "piecePlacement": "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R",
          "turn": "w",
          "castling": "KQkq",
          "orientation": "black",
          "isLegal": true,
          "confidence": "high",
          "notes": "Diagram viewed from Black's side.",
          "quota": { "used": 1, "limit": 1, "remaining": 0, "isPro": false }
        }
        """.utf8)

        let result = try BoardVisionService.decodeResponse(data: okJSON, statusCode: 200)
        #expect(result.boardOrientation == .black)
        #expect(result.sideToMove == .white)
        #expect(result.quota?.remaining == 0)
        #expect(result.quota?.isPro == false)

        let free429 = Data(#"{"error":"free_limit_reached","limit":1,"used":1,"isPro":false}"#.utf8)
        #expect(throws: BoardVisionError.freeLimitReached(used: 1, limit: 1)) {
            try BoardVisionService.decodeResponse(data: free429, statusCode: 429)
        }

        let pro429 = Data(#"{"error":"daily_limit_reached","limit":100,"used":100,"isPro":true}"#.utf8)
        #expect(throws: BoardVisionError.dailyLimitReached(used: 100, limit: 100)) {
            try BoardVisionService.decodeResponse(data: pro429, statusCode: 429)
        }
    }

    @Test("extracts pieces, turn, and castling and rebuilds playable FEN on turn toggle")
    func extractsAndRebuildsFEN() throws {
        var extracted = BoardVisionService.extractBoard(
            from: "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2"
        )
        #expect(extracted.sideToMove == .black)
        #expect(extracted.castling == .all)
        #expect(extracted.position?.sideToMove == .black)

        extracted.sideToMove = .white
        let flipped = try #require(extracted.position)
        #expect(flipped.sideToMove == .white)
        #expect(flipped.castling == .all)
    }
}
