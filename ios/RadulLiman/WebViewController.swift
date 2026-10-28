import UIKit
import WebKit
import CoreLocation
import Photos

/// Окно с веб-страницей приложения (тот же index.html, что и в Android-версии).
/// Страница получает от этого класса то, что в браузере недоступно или неудобно:
/// координаты (CoreLocation), перенос в «Фото» по команде, удаление копий, меню «Поделиться».
final class WebViewController: UIViewController,
                               WKNavigationDelegate,
                               WKUIDelegate,
                               WKScriptMessageHandlerWithReply,
                               CLLocationManagerDelegate {

    private var web: WKWebView!
    private let locationManager = CLLocationManager()
    private var wentToBackground: Date?
    private let bgColor = UIColor(red: 20 / 255, green: 24 / 255, blue: 26 / 255, alpha: 1)

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    // MARK: - Окно

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = bgColor

        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.userContentController.addScriptMessageHandler(self, contentWorld: .page, name: "rl")

        let webView = WKWebView(frame: view.bounds, configuration: config)
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.isOpaque = false
        webView.backgroundColor = bgColor
        webView.scrollView.backgroundColor = bgColor
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.scrollView.bounces = false
        webView.scrollView.pinchGestureRecognizer?.isEnabled = false
        view.addSubview(webView)
        web = webView

        NotificationCenter.default.addObserver(self, selector: #selector(didEnterBackground),
                                               name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(willEnterForeground),
                                               name: UIApplication.willEnterForegroundNotification, object: nil)
        loadPage()
    }

    private func loadPage() {
        guard let url = Bundle.main.url(forResource: "index", withExtension: "html"),
              let html = try? String(contentsOf: url, encoding: .utf8) else {
            NSLog("RL: index.html not found in bundle")
            return
        }
        // https-адрес нужен, чтобы страница считалась защищённой: без этого камера и пароль (Web Crypto) недоступны.
        // Сеть при этом не используется, страница целиком лежит внутри приложения.
        web.loadHTMLString(html, baseURL: URL(string: "https://localhost/"))
    }

    private func js(_ code: String) {
        web.evaluateJavaScript(code, completionHandler: nil)
    }

    private func jsonString(_ object: Any) -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8) else { return "null" }
        return text
    }

    @objc private func didEnterBackground() {
        wentToBackground = Date()
    }

    /// Страница сама решает, спрашивать ли пароль: ей нужно знать, сколько приложение пробыло в фоне.
    @objc private func willEnterForeground() {
        guard let since = wentToBackground else { return }
        wentToBackground = nil
        let ms = Int(Date().timeIntervalSince(since) * 1000)
        js("window.__resume&&window.__resume(\(ms))")
    }

    // MARK: - Камера

    func webView(_ webView: WKWebView,
                 requestMediaCapturePermissionFor origin: WKSecurityOrigin,
                 initiatedByFrame frame: WKFrameInfo,
                 type: WKMediaCaptureType,
                 decisionHandler: @escaping (WKPermissionDecision) -> Void) {
        // Страница своя, лежит внутри приложения. Системный запрос доступа к камере всё равно покажет сам iOS.
        decisionHandler(.grant)
    }

    // MARK: - Сообщения от страницы

    func userContentController(_ userContentController: WKUserContentController,
                               didReceive message: WKScriptMessage,
                               replyHandler: @escaping (Any?, String?) -> Void) {
        guard let body = message.body as? [String: Any], let action = body["a"] as? String else {
            replyHandler(nil, "bad message")
            return
        }
        switch action {
        case "geo":
            startLocation()
            replyHandler("ok", nil)
        case "save":
            savePhoto(body, reply: replyHandler)
        case "share":
            sharePhoto(body)
            replyHandler("ok", nil)
        case "del":
            deletePhotos(body)
            replyHandler("ok", nil)
        case "log":
            NSLog("RL: %@", (body["m"] as? String) ?? "")
            replyHandler("ok", nil)
        default:
            replyHandler(nil, "unknown action")
        }
    }

    // MARK: - Координаты

    private func startLocation() {
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = kCLDistanceFilterNone
        switch locationManager.authorizationStatus {
        case .notDetermined:
            locationManager.requestWhenInUseAuthorization()
        case .denied, .restricted:
            js("window.__geoErr&&window.__geoErr(1)")
        default:
            beginUpdates()
        }
    }

    private func beginUpdates() {
        locationManager.startUpdatingLocation()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            beginUpdates()
        case .denied, .restricted:
            js("window.__geoErr&&window.__geoErr(1)")
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        var data: [String: Any] = [
            "lat": loc.coordinate.latitude,
            "lon": loc.coordinate.longitude
        ]
        if loc.horizontalAccuracy >= 0 { data["acc"] = loc.horizontalAccuracy }
        if loc.verticalAccuracy >= 0 { data["alt"] = loc.altitude }
        js("window.__geo&&window.__geo(\(jsonString(data)))")
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        guard let clError = error as? CLError else { return }
        switch clError.code {
        case .denied:
            js("window.__geoErr&&window.__geoErr(1)")
        case .locationUnknown:
            break // временно нет сигнала, менеджер продолжит сам
        default:
            js("window.__geoErr&&window.__geoErr(2)")
        }
    }

    // MARK: - Фото: сохранить, отправить, удалить

    private func savePhoto(_ body: [String: Any], reply: @escaping (Any?, String?) -> Void) {
        guard let text = body["data"] as? String, let data = Data(base64Encoded: text) else {
            reply("", nil)
            return
        }
        let name = (body["name"] as? String).map { ($0 as NSString).lastPathComponent } ?? "photo.jpg"
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                DispatchQueue.main.async { reply("", nil) }
                return
            }
            var identifier = ""
            PHPhotoLibrary.shared().performChanges({
                let request = PHAssetCreationRequest.forAsset()
                let options = PHAssetResourceCreationOptions()
                options.originalFilename = name
                request.addResource(with: .photo, data: data, options: options)
                identifier = request.placeholderForCreatedAsset?.localIdentifier ?? ""
            }, completionHandler: { success, _ in
                DispatchQueue.main.async {
                    // Страница запоминает этот код, чтобы потом удалить копию из «Фото».
                    reply(success && !identifier.isEmpty ? "ios:" + identifier : "", nil)
                }
            })
        }
    }

    private func sharePhoto(_ body: [String: Any]) {
        guard let text = body["data"] as? String, let data = Data(base64Encoded: text) else { return }
        let name = (body["name"] as? String).map { ($0 as NSString).lastPathComponent } ?? "photo.jpg"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        do { try data.write(to: url) } catch { return }
        let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        sheet.popoverPresentationController?.sourceView = view
        sheet.popoverPresentationController?.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1)
        present(sheet, animated: true)
    }

    private func deletePhotos(_ body: [String: Any]) {
        let ids = (body["ids"] as? [String] ?? [])
            .filter { $0.hasPrefix("ios:") }
            .map { String($0.dropFirst(4)) }
        guard !ids.isEmpty else { return }
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
            guard status == .authorized || status == .limited else { return }
            let assets = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)
            guard assets.count > 0 else { return }
            // iOS сам спросит подтверждение удаления.
            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.deleteAssets(assets)
            }, completionHandler: nil)
        }
    }
}
