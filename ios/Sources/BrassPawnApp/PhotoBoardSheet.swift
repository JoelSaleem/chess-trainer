import BoardUI
import ChessCore
import ChessTraining
import PhotosUI
import SwiftUI

#if canImport(UIKit)
import UIKit

/// The camera, for a board that is in front of you rather than in your library.
private struct CameraPicker: UIViewControllerRepresentable {
    let onCapture: (UIImage) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        private let parent: CameraPicker
        init(_ parent: CameraPicker) { self.parent = parent }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            if let image = info[.originalImage] as? UIImage { parent.onCapture(image) }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}

/// `PhotosPicker` takes its label in a sendable closure, which cannot reach
/// main-actor state — so a `View` with no stored properties builds the label.
private struct ChooseLabel: View {
    var body: some View {
        HStack(spacing: 7) {
            Image(systemName: "photo.on.rectangle")
                .font(.system(size: 14, weight: .semibold))
            Text(L.t("photo.choose", "Choose a photo"))
                .appFont(.body, weight: .semibold)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        .background { BrassPlateShape(cut: 10).fill(Theatre.brass.opacity(0.12)) }
        .overlay { BrassPlateShape(cut: 10).strokeBorder(Theatre.brass.opacity(0.5), lineWidth: 0.8) }
        .foregroundStyle(Theatre.brass)
    }
}

/// Preview board fallback when AI recognition finds pieces that do not yet form
/// a legal `Position` (for instance, an occluded king that needs a tap in the editor).
private struct RecognizedPiecesBoard: View {
    @Environment(\.boardTheme) private var theme
    let pieces: [Square: Piece]
    let orientation: PieceColor

    var body: some View {
        GeometryReader { geometry in
            let squareSize = geometry.size.width / 8
            VStack(spacing: 0) {
                ForEach(0..<8, id: \.self) { row in
                    HStack(spacing: 0) {
                        ForEach(0..<8, id: \.self) { column in
                            let sq = Square(
                                file: orientation == .white ? column : 7 - column,
                                rank: orientation == .white ? 7 - row : row
                            )
                            ZStack {
                                ((sq.file + sq.rank) % 2 == 0 ? theme.darkSquare : theme.lightSquare)
                                if let piece = pieces[sq] {
                                    PieceView(piece: piece, size: squareSize)
                                        .padding(squareSize * 0.06)
                                }
                            }
                            .frame(width: squareSize, height: squareSize)
                        }
                    }
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(.rect(cornerRadius: 6))
    }
}

/// Read a position off a photograph, screenshot, or diagram — either in one tap
/// with AI board vision (`gpt-6.1-terra`) or by dragging the four board corners
/// for manual adjustment in the position editor.
struct PhotoBoardSheet: View {
    @Environment(AppModel.self) private var app
    @Binding var isPresented: Bool
    let reader: any SquareReader
    let onPlayOnBoard: (Position, PieceColor) -> Void
    let onEditPosition: (BoardVisionService.ExtractedBoard, PieceColor?) -> Void

    @State private var picked: PhotosPickerItem?
    @State private var image: UIImage?
    /// Four corners in image pixel coordinates for manual rectification.
    @State private var corners: [CGPoint] = []
    @State private var dragging: Int?
    @State private var isCapturing = false
    @State private var isReadingManual = false
    @State private var detected = false
    @State private var manualCropMode = false

    /// AI recognition state.
    @State private var isRecognizing = false
    @State private var visionResult: BoardVisionResult?
    @State private var extracted = BoardVisionService.ExtractedBoard(pieces: [:], sideToMove: .white, castling: [])
    @State private var previewOrientation: PieceColor = .white
    @State private var visionError: BoardVisionError?
    @State private var pasteNote: String?
    @State private var showsPaywall = false

    private var canUseCamera: Bool {
        !ProcessInfo.processInfo.isiOSAppOnMac && UIImagePickerController.isSourceTypeAvailable(.camera)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(L.t("photo.title", "Read a board from a photo"))
                    .appFont(size: 22, weight: .semibold)
                    .foregroundStyle(Theatre.ivory)

                if let result = visionResult {
                    recognizedContent(result)
                } else if let image {
                    loadedPhotoContent(image)
                } else {
                    emptyPickerContent
                }

                Button(L.t("common.cancel", "Cancel")) { isPresented = false }
                    .buttonStyle(PillButtonStyle(emphasis: .ghost, usesBodySize: true))
                    .padding(.top, 4)
            }
            .padding(20)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Theatre.ink.ignoresSafeArea())
        .appCover(isPresented: $isCapturing) {
            CameraPicker { captured in
                acceptNewImage(captured)
            }
            .ignoresSafeArea()
        }
        .appCover(isPresented: $showsPaywall) {
            PaywallView(boardScanLimit: true)
        }
        .onChange(of: app.store.isPro) { _, isPro in
            if isPro, case .freeLimitReached = visionError {
                visionError = nil
            }
        }
        .onChange(of: picked) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self),
                   let loaded = UIImage(data: data) {
                    acceptNewImage(loaded)
                }
            }
        }
    }

    // MARK: - Step 1: Pick, capture, or paste an image

    @ViewBuilder
    private var emptyPickerContent: some View {
        Card {
            Text(L.t("photo.hintAI",
                     "Photograph a real chessboard, choose a screenshot from Photos, or paste a diagram from your clipboard. AI recognition reads the pieces, side to move, and board orientation in one tap."))
                .appFont(.footnote)
                .foregroundStyle(Theatre.ivoryDim)
                .fixedSize(horizontal: false, vertical: true)
        }

        if let pasteNote {
            Text(pasteNote)
                .appFont(.footnote)
                .foregroundStyle(Theatre.bad)
        }

        VStack(spacing: 10) {
            if canUseCamera {
                Button {
                    isCapturing = true
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "camera.fill")
                            .font(.system(size: 14, weight: .semibold))
                        Text(L.t("photo.camera", "Take a photo"))
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(PillButtonStyle(emphasis: .solid, usesBodySize: true))
            }

            PhotosPicker(selection: $picked, matching: .images) {
                ChooseLabel()
            }

            Button {
                pasteFromClipboard()
            } label: {
                HStack(spacing: 7) {
                    Image(systemName: "doc.on.clipboard")
                        .font(.system(size: 14, weight: .semibold))
                    Text(L.t("photo.pasteImage", "Paste image"))
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(PillButtonStyle(emphasis: .ghost, usesBodySize: true))
        }
        .padding(.vertical, 8)
    }

    // MARK: - Step 2: AI one-tap recognition or manual 4-corner crop

    @ViewBuilder
    private func loadedPhotoContent(_ image: UIImage) -> some View {
        if manualCropMode {
            Text(detected
                 ? L.t("photo.foundBoard", "Board found. Drag a handle if any corner is not on it.")
                 : L.t("photo.dragCorners", "Drag the four handles onto the corners of the board."))
                .appFont(.footnote)
                .foregroundStyle(detected ? Theatre.brass : Theatre.ivoryDim)

            adjustable(image)
                .frame(height: 330)

            HStack(spacing: 8) {
                Button(L.t("photo.backToAI", "AI scan")) {
                    manualCropMode = false
                }
                .buttonStyle(PillButtonStyle(emphasis: .ghost, usesBodySize: true))

                Spacer()

                Button(L.t("photo.read", "Read it")) { readManual(image) }
                    .buttonStyle(PillButtonStyle(
                        emphasis: .solid, enabled: !isReadingManual, usesBodySize: true
                    ))
                    .disabled(isReadingManual)
            }
        } else {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: .infinity, maxHeight: 300)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay {
                    RoundedRectangle(cornerRadius: 10)
                        .strokeBorder(Theatre.brassDeep.opacity(0.55), lineWidth: 0.8)
                }

            if let visionError {
                errorBanner(visionError)
            }

            Button {
                recognizeWithAI(image)
            } label: {
                HStack(spacing: 9) {
                    if isRecognizing {
                        BrassActivityIndicator(size: 15)
                        Text(L.t("photo.recognizing", "Recognizing board…"))
                    } else {
                        BrassIcon("sparkles", size: 17)
                        Text(L.t("photo.recognizeAI", "Recognize Position (AI)"))
                    }
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(PillButtonStyle(emphasis: .solid, enabled: !isRecognizing, usesBodySize: true))
            .disabled(isRecognizing)

            HStack(spacing: 8) {
                Button(L.t("photo.again", "Start again")) {
                    resetAll()
                }
                .buttonStyle(PillButtonStyle(emphasis: .ghost, usesBodySize: true))
                .disabled(isRecognizing)

                Spacer()

                Button(L.t("photo.manualCrop", "Manual corner crop")) {
                    manualCropMode = true
                }
                .buttonStyle(PillButtonStyle(emphasis: .ghost, usesBodySize: true))
                .disabled(isRecognizing)
            }
        }
    }

    @ViewBuilder
    private func errorBanner(_ error: BoardVisionError) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(error.message)
                .appFont(.footnote)
                .foregroundStyle(error == .serviceBusy ? Theatre.ivoryDim : Theatre.ivory)
                .fixedSize(horizontal: false, vertical: true)

            if case .freeLimitReached = error {
                Button {
                    showsPaywall = true
                } label: {
                    Text(L.t("photo.upgradePro", "Upgrade to Brass Pawn Pro"))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(PillButtonStyle(emphasis: .solid, usesBodySize: true))
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            BrassPlateShape(cut: 10).fill(Theatre.ink3)
        }
        .overlay {
            BrassPlateShape(cut: 10)
                .strokeBorder(Theatre.brassHot.opacity(0.72), lineWidth: 0.85)
        }
    }

    // MARK: - Step 3: Recognized board preview & actions

    @ViewBuilder
    private func recognizedContent(_ result: BoardVisionResult) -> some View {
        let playablePosition = extracted.position
        let editorValidation = PositionEditorModel(pieces: extracted.pieces, orientation: previewOrientation)

        VStack(alignment: .leading, spacing: 12) {
            Group {
                if let playablePosition {
                    BoardView(
                        position: playablePosition,
                        orientation: previewOrientation
                    )
                    .aspectRatio(1, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                } else {
                    RecognizedPiecesBoard(
                        pieces: extracted.pieces,
                        orientation: previewOrientation
                    )
                }
            }
            .frame(maxWidth: 330)
            .frame(maxWidth: .infinity, alignment: .center)

            Card {
                HStack(spacing: 8) {
                    sideToMoveBadge
                    Spacer(minLength: 6)
                    Button {
                        previewOrientation = previewOrientation.opponent
                    } label: {
                        HStack(spacing: 5) {
                            BrassIcon("arrow.clockwise", size: 13)
                            Text(L.t("board.flip", "Flip"))
                        }
                        .appFont(.caption, weight: .semibold)
                        .foregroundStyle(Theatre.brass)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Theatre.brass.opacity(0.12), in: .rect(cornerRadius: 7))
                    }
                    .buttonStyle(.plain)
                }

                if let quota = result.quota {
                    Text(quotaText(quota))
                        .appFont(.caption)
                        .foregroundStyle(Theatre.brass)
                }

                if let notes = result.notes?.trimmingCharacters(in: .whitespacesAndNewlines),
                   !notes.isEmpty {
                    Text(notes)
                        .appFont(.footnote)
                        .foregroundStyle(Theatre.ivoryDim)
                        .fixedSize(horizontal: false, vertical: true)
                }

                if playablePosition == nil {
                    let problemText = {
                        editorValidation.sideToMove = extracted.sideToMove
                        return editorValidation.problem
                            ?? L.t("editor.unusable", "This is not a position that can be played.")
                    }()
                    Text(problemText)
                        .appFont(.footnote)
                        .foregroundStyle(Theatre.bad)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            VStack(spacing: 8) {
                Button {
                    guard let playablePosition else { return }
                    onPlayOnBoard(playablePosition, previewOrientation)
                    isPresented = false
                } label: {
                    Text(L.t("photo.playOnBoard", "Play on Board"))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(PillButtonStyle(
                    emphasis: .solid,
                    enabled: playablePosition != nil,
                    usesBodySize: true
                ))
                .disabled(playablePosition == nil)

                HStack(spacing: 8) {
                    Button {
                        onEditPosition(extracted, previewOrientation)
                        isPresented = false
                    } label: {
                        Text(L.t("photo.editPosition", "Edit Position"))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(PillButtonStyle(emphasis: .ghost, usesBodySize: true))

                    Button(L.t("photo.again", "Start again")) {
                        resetAll()
                    }
                    .buttonStyle(PillButtonStyle(emphasis: .ghost, usesBodySize: true))
                }
            }
        }
    }

    private var sideToMoveBadge: some View {
        HStack(spacing: 6) {
            ForEach([PieceColor.white, PieceColor.black], id: \.self) { color in
                let selected = extracted.sideToMove == color
                Button {
                    extracted.sideToMove = color
                } label: {
                    Text(color == .white
                         ? L.t("photo.whiteToMove", "White to move")
                         : L.t("photo.blackToMove", "Black to move"))
                        .appFont(.caption, weight: selected ? .semibold : .regular)
                        .foregroundStyle(selected ? Theatre.brassHot : Theatre.ivoryDim)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(
                            Theatre.brass.opacity(selected ? 0.22 : 0.06),
                            in: .rect(cornerRadius: 7)
                        )
                        .overlay {
                            if selected {
                                RoundedRectangle(cornerRadius: 7)
                                    .strokeBorder(Theatre.brass.opacity(0.65), lineWidth: 0.8)
                            }
                        }
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func quotaText(_ quota: BoardVisionResult.Quota) -> String {
        if quota.isPro {
            return L.t("photo.scansRemainingToday", "%1$lld/%2$lld scans left today",
                       Int64(quota.remaining), Int64(quota.limit))
        }
        return L.t("photo.scansRemainingFree", "%1$lld/%2$lld free scans left today",
                   Int64(quota.remaining), Int64(quota.limit))
    }

    // MARK: - Helpers

    private func acceptNewImage(_ loaded: UIImage) {
        corners = []
        detected = false
        manualCropMode = false
        visionResult = nil
        visionError = nil
        pasteNote = nil
        image = loaded
    }

    private func resetAll() {
        corners = []
        detected = false
        manualCropMode = false
        visionResult = nil
        visionError = nil
        pasteNote = nil
        picked = nil
        image = nil
    }

    private func pasteFromClipboard() {
        pasteNote = nil
        if let pasted = UIPasteboard.general.image {
            acceptNewImage(pasted)
            return
        }
        for type in ["public.png", "public.jpeg", "public.tiff", "public.image"] {
            if let data = UIPasteboard.general.data(forPasteboardType: type),
               let pasted = UIImage(data: data) {
                acceptNewImage(pasted)
                return
            }
        }
        pasteNote = L.t("photo.noClipboardImage", "No image was found on the clipboard.")
    }

    private func recognizeWithAI(_ image: UIImage) {
        guard !isRecognizing else { return }
        visionError = nil
        isRecognizing = true
        Task {
            do {
                let result = try await BoardVisionService.recognize(image: image)
                extracted = BoardVisionService.extractBoard(from: result.fen)
                previewOrientation = result.boardOrientation
                visionResult = result
            } catch let error as BoardVisionError {
                visionError = error
            } catch {
                visionError = .networkUnavailable
            }
            isRecognizing = false
        }
    }

    // MARK: - Manual four-corner crop

    private func adjustable(_ image: UIImage) -> some View {
        GeometryReader { geometry in
            let scale = min(geometry.size.width / image.size.width,
                            geometry.size.height / image.size.height)
            let shown = CGSize(width: image.size.width * scale,
                               height: image.size.height * scale)

            ZStack(alignment: .topLeading) {
                Image(uiImage: image)
                    .resizable()
                    .frame(width: shown.width, height: shown.height)

                quadOutline(scale: scale)

                ForEach(corners.indices, id: \.self) { index in
                    handle(index: index, scale: scale, image: image)
                }

                if let dragging, corners.indices.contains(dragging) {
                    loupe(image: image, at: corners[dragging], shown: shown, scale: scale)
                }
            }
            .frame(width: shown.width, height: shown.height)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
            .task(id: image) { await placeCorners(in: image) }
        }
    }

    private func quadOutline(scale: CGFloat) -> some View {
        Path { path in
            guard corners.count == 4 else { return }
            let points = corners.map { CGPoint(x: $0.x * scale, y: $0.y * scale) }
            path.addLines(points)
            path.closeSubpath()
        }
        .stroke(Theatre.brass.opacity(0.9), lineWidth: 1.5)
    }

    private func handle(index: Int, scale: CGFloat, image: UIImage) -> some View {
        let point = corners[index]
        return Circle()
            .strokeBorder(Theatre.brass, lineWidth: 2)
            .background(Circle().fill(Theatre.brass.opacity(dragging == index ? 0.45 : 0.2)))
            .frame(width: 30, height: 30)
            .contentShape(Circle().inset(by: -14))
            .position(x: point.x * scale, y: point.y * scale)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        dragging = index
                        corners[index] = clamp(
                            CGPoint(x: value.location.x / scale, y: value.location.y / scale),
                            to: image.size
                        )
                    }
                    .onEnded { _ in dragging = nil }
            )
    }

    private func loupe(image: UIImage, at point: CGPoint, shown: CGSize, scale: CGFloat) -> some View {
        let size: CGFloat = 108
        let magnification: CGFloat = 2.6
        let onTheLeft = point.x * scale > shown.width / 2
        let onTop = point.y * scale > shown.height / 2

        return ZStack {
            Image(uiImage: image)
                .resizable()
                .frame(width: image.size.width * scale * magnification,
                       height: image.size.height * scale * magnification)
                .offset(x: size / 2 - point.x * scale * magnification,
                        y: size / 2 - point.y * scale * magnification)
            Path { path in
                path.move(to: CGPoint(x: size / 2, y: 0))
                path.addLine(to: CGPoint(x: size / 2, y: size))
                path.move(to: CGPoint(x: 0, y: size / 2))
                path.addLine(to: CGPoint(x: size, y: size / 2))
            }
            .stroke(Theatre.brass.opacity(0.85), lineWidth: 1)
        }
        .frame(width: size, height: size)
        .clipShape(.circle)
        .overlay { Circle().strokeBorder(Theatre.brass, lineWidth: 1.5) }
        .position(
            x: onTheLeft ? size / 2 + 8 : shown.width - size / 2 - 8,
            y: onTop ? size / 2 + 8 : shown.height - size / 2 - 8
        )
        .allowsHitTesting(false)
    }

    private func placeCorners(in image: UIImage) async {
        guard corners.isEmpty else { return }
        let fallback = insetCorners(in: image)

        guard let cgImage = image.cgImage else {
            corners = fallback
            detected = false
            return
        }
        let found = await Task.detached(priority: .userInitiated) {
            BoardDetector.corners(in: cgImage)
        }.value

        guard let found else {
            corners = fallback
            detected = false
            return
        }
        withAnimation(.easeOut(duration: 0.25)) {
            corners = found.all
            detected = true
        }
    }

    private func insetCorners(in image: UIImage) -> [CGPoint] {
        let inset = CGPoint(x: image.size.width * 0.12, y: image.size.height * 0.12)
        return [
            CGPoint(x: inset.x, y: inset.y),
            CGPoint(x: image.size.width - inset.x, y: inset.y),
            CGPoint(x: image.size.width - inset.x, y: image.size.height - inset.y),
            CGPoint(x: inset.x, y: image.size.height - inset.y),
        ]
    }

    private func clamp(_ point: CGPoint, to size: CGSize) -> CGPoint {
        CGPoint(x: min(max(0, point.x), size.width), y: min(max(0, point.y), size.height))
    }

    private func readManual(_ image: UIImage) {
        guard let cgImage = image.cgImage,
              let ordered = BoardGeometry.order(corners),
              let rectified = BoardPhoto.rectify(cgImage, corners: ordered) else { return }

        isReadingManual = true
        Task {
            let found = await reader.read(BoardPhoto.crops(from: rectified))
            isReadingManual = false
            onEditPosition(
                BoardVisionService.ExtractedBoard(pieces: found, sideToMove: .white, castling: []),
                nil
            )
            isPresented = false
        }
    }
}
#endif
