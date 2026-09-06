// macOS-only screenshot text/bounds verification, including Canvas-drawn field captions.
import Foundation
import Vision
guard CommandLine.arguments.count == 2 else { fatalError("Usage: swift tools/ocr-screenshot.swift screenshot.png") }
let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.recognitionLanguages = ["zh-Hans", "en-US"]
request.usesLanguageCorrection = false
try VNImageRequestHandler(url: URL(fileURLWithPath: CommandLine.arguments[1])).perform([request])
for item in request.results ?? [] {
    guard let text = item.topCandidates(1).first?.string else { continue }
    print(String(format: "%.3f %.3f %.3f %.3f", item.boundingBox.minX, item.boundingBox.minY, item.boundingBox.width, item.boundingBox.height), text)
}
