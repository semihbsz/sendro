//
//  SendroClient.swift
//  Sendro
//
//  Typed HTTP client for one Sendro host (http://ip:port), PROTOCOL.md v1.
//

import Foundation

enum SendroClientError: Error, LocalizedError {
    case badURL
    case badResponse
    case http(status: Int, code: String?, message: String?)
    case decoding

    var errorDescription: String? {
        switch self {
        case .badURL:
            return "Invalid host address."
        case .badResponse:
            return "Unexpected response from host."
        case .http(let status, let code, let message):
            if let message, !message.isEmpty { return message }
            if let code, !code.isEmpty { return "\(code) (HTTP \(status))" }
            return "HTTP \(status)"
        case .decoding:
            return "Could not read the host's response."
        }
    }

    var apiCode: String? {
        if case .http(_, let code, _) = self { return code }
        return nil
    }

    var httpStatus: Int? {
        if case .http(let status, _, _) = self { return status }
        return nil
    }
}

struct SendroClient {

    let baseURL: URL
    var token: String?

    /// Shared session for API calls (downloads use their own session).
    /// Fail-fast on purpose: ping / accept / status should error immediately
    /// when the host is unreachable so callers can react.
    private static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = false
        config.timeoutIntervalForRequest = 40   // long poll 25s + slack
        config.timeoutIntervalForResource = 120
        config.httpAdditionalHeaders = ["Accept": "application/json"]
        return URLSession(configuration: config)
    }()

    /// Session dedicated to the outbox long poll. Unlike the fail-fast API
    /// session it waits for connectivity, so a transient Wi-Fi blip parks the
    /// poll instead of erroring it into backoff; the resource timeout still
    /// bounds every attempt so the poll loop can never hang forever.
    private static let pollSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = true
        config.timeoutIntervalForRequest = 40   // long poll 25s + slack
        config.timeoutIntervalForResource = 90  // caps time spent waiting for connectivity
        config.httpAdditionalHeaders = ["Accept": "application/json"]
        return URLSession(configuration: config)
    }()

    init?(host: String, port: UInt16, token: String? = nil) {
        // Be forgiving about what lands here (manual entry, resolver quirks):
        // trim whitespace, drop a pasted scheme/trailing slash, strip any
        // interface scope ("%en0"), and bracket bare IPv6 literals.
        var cleaned = host.trimmingCharacters(in: .whitespacesAndNewlines)
        for scheme in ["http://", "https://"] where cleaned.lowercased().hasPrefix(scheme) {
            cleaned = String(cleaned.dropFirst(scheme.count))
        }
        if let slash = cleaned.firstIndex(of: "/") { cleaned = String(cleaned[..<slash]) }
        if !cleaned.hasPrefix("["), let pct = cleaned.firstIndex(of: "%") {
            cleaned = String(cleaned[..<pct])
        }
        if cleaned.contains(":") && !cleaned.hasPrefix("[") {
            // Either a bare IPv6 literal, or "ip:port" pasted into the host
            // field. Two or more colons → IPv6, bracket it. Exactly one
            // colon → treat the tail as a (discarded) port.
            let colonCount = cleaned.filter { $0 == ":" }.count
            if colonCount >= 2 {
                cleaned = "[\(cleaned)]"
            } else if let colon = cleaned.firstIndex(of: ":") {
                cleaned = String(cleaned[..<colon])
            }
        }
        guard !cleaned.isEmpty, let url = URL(string: "http://\(cleaned):\(port)") else { return nil }
        self.baseURL = url
        self.token = token
    }

    // MARK: - Endpoints

    func info() async throws -> InfoResponse {
        try await get("/api/v1/info", timeout: 8)
    }

    func pairStart(_ request: PairStartRequest) async throws -> PairStartResponse {
        try await post("/api/v1/pair/start", body: request, timeout: 10)
    }

    func pairConfirm(_ request: PairConfirmRequest) async throws -> PairConfirmResponse {
        try await post("/api/v1/pair/confirm", body: request, timeout: 10)
    }

    func ping() async throws -> PingResponse {
        try await get("/api/v1/ping", timeout: 6)
    }

    func outboxLongPoll(waitSeconds: Int = 25) async throws -> OutboxResponse {
        try await get("/api/v1/outbox?waitSeconds=\(waitSeconds)",
                      timeout: TimeInterval(waitSeconds + 12),
                      session: SendroClient.pollSession)
    }

    @discardableResult
    func accept(transferId: String) async throws -> OkResponse {
        try await post("/api/v1/transfers/\(transferId)/accept", timeout: 10)
    }

    @discardableResult
    func reject(transferId: String) async throws -> OkResponse {
        try await post("/api/v1/transfers/\(transferId)/reject", timeout: 10)
    }

    @discardableResult
    func reportStatus(transferId: String, _ report: StatusReport) async throws -> OkResponse {
        try await post("/api/v1/transfers/\(transferId)/status", body: report, timeout: 10)
    }

    /// Build the GET request for the file bytes (PROTOCOL.md §6.4).
    /// `rangeStart` != nil resumes from that offset with an If-Range guard.
    func makeFileRequest(transferId: String, rangeStart: Int64?, sha256: String) -> URLRequest {
        var request = URLRequest(url: baseURL.appendingPathComponent("api/v1/transfers/\(transferId)/file"))
        request.httpMethod = "GET"
        applyAuth(&request)
        if let rangeStart, rangeStart > 0 {
            request.setValue("bytes=\(rangeStart)-", forHTTPHeaderField: "Range")
            request.setValue("\"\(sha256.lowercased())\"", forHTTPHeaderField: "If-Range")
        }
        request.timeoutInterval = 60
        return request
    }

    // MARK: - Plumbing

    private func applyAuth(_ request: inout URLRequest) {
        if let token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
    }

    private func get<Response: Decodable>(_ path: String,
                                          timeout: TimeInterval,
                                          session: URLSession = SendroClient.session) async throws -> Response {
        guard let url = URL(string: path, relativeTo: baseURL) else {
            throw SendroClientError.badURL
        }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = timeout
        applyAuth(&request)
        return try await execute(request, session: session)
    }

    private func post<Response: Decodable>(_ path: String, timeout: TimeInterval) async throws -> Response {
        guard let url = URL(string: path, relativeTo: baseURL) else {
            throw SendroClientError.badURL
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = timeout
        applyAuth(&request)
        return try await execute(request)
    }

    private func post<Body: Encodable, Response: Decodable>(
        _ path: String, body: Body, timeout: TimeInterval
    ) async throws -> Response {
        guard let url = URL(string: path, relativeTo: baseURL) else {
            throw SendroClientError.badURL
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = timeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)
        applyAuth(&request)
        return try await execute(request)
    }

    private func execute<Response: Decodable>(_ request: URLRequest,
                                              session: URLSession = SendroClient.session) async throws -> Response {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw SendroClientError.badResponse
        }
        guard (200..<300).contains(http.statusCode) else {
            if let apiError = try? JSONDecoder().decode(ApiError.self, from: data) {
                throw SendroClientError.http(status: http.statusCode,
                                             code: apiError.error,
                                             message: apiError.message)
            }
            throw SendroClientError.http(status: http.statusCode, code: nil, message: nil)
        }
        do {
            return try JSONDecoder().decode(Response.self, from: data)
        } catch {
            throw SendroClientError.decoding
        }
    }
}
