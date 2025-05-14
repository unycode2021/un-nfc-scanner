import Foundation

@objc public class NFCScanner: NSObject {
    @objc public func echo(_ value: String) -> String {
        print(value)
        return value
    }
}
