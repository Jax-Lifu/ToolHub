mod auth;
mod storage;

use auth::{
    load_or_create_secret, load_users, make_user, save_users, sign_token, verify_password,
    verify_token, Role, TokenClaims, UserRecord, UserView,
};
use axum::{
    body::Body,
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        ConnectInfo, Path, Query, Request, State,
    },
    http::{header, HeaderMap, Method, StatusCode},
    response::{IntoResponse, Response},
    routing::{any, get, patch, post},
    Json, Router,
};
use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
use chrono::Utc;
use futures_util::{SinkExt, StreamExt};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    env, fs,
    net::SocketAddr,
    path::PathBuf,
    sync::Arc,
};
use storage::{
    append_audit_entry, append_telemetry_point, ensure_ssh_authorized_key, read_audit_entries,
    read_telemetry_history, AuditEntry, TelemetryPoint,
};
use tokio::sync::{mpsc, oneshot, RwLock};
use tower_http::{
    cors::CorsLayer,
    services::{ServeDir, ServeFile},
};
use uuid::Uuid;

#[derive(Clone)]
struct AppState {
    devices: Arc<RwLock<HashMap<String, DeviceRecord>>>,
    users: Arc<RwLock<HashMap<String, UserRecord>>>,
    last_history_ts: Arc<RwLock<HashMap<String, (i64, bool)>>>,
    ws_tunnels: Arc<RwLock<HashMap<String, WsTunnelEntry>>>,
    pending_http: Arc<RwLock<HashMap<String, oneshot::Sender<WsHttpResp>>>>,
    pending_chunks: Arc<RwLock<HashMap<String, ChunkAccumulator>>>,
    http_client: Client,
    jwt_secret: Vec<u8>,
    device_token: String,
    ssh_host: String,
    ssh_user: String,
    data_file: String,
    users_file: PathBuf,
    history_dir: PathBuf,
    audit_file: PathBuf,
}

#[derive(Clone)]
struct WsTunnelEntry {
    conn_id: String,
    tx: mpsc::Sender<String>,
}

struct ChunkAccumulator {
    status: u16,
    headers: HashMap<String, String>,
    bytes: Vec<u8>,
}

#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum WsServerMsg {
    HttpReq {
        id: String,
        method: String,
        path: String,
        headers: HashMap<String, String>,
        #[serde(rename = "bodyB64")]
        body_b64: String,
    },
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum WsClientMsg {
    Hello {
        heartbeat: HeartbeatPayload,
    },
    Heartbeat {
        heartbeat: HeartbeatPayload,
    },
    HttpResp(WsHttpResp),
    HttpRespChunk(WsHttpChunk),
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WsHttpResp {
    id: String,
    status: u16,
    #[serde(default)]
    headers: HashMap<String, String>,
    #[serde(default)]
    body_b64: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WsHttpChunk {
    id: String,
    #[serde(default)]
    status: u16,
    #[serde(default)]
    headers: HashMap<String, String>,
    #[serde(default)]
    body_b64: String,
    #[serde(default)]
    eof: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct HeartbeatPayload {
    mac: String,
    serial: String,
    model: String,
    brand: String,
    android_version: String,
    sdk_int: i32,
    soc_platform: String,
    lan_ip: String,
    app_version: String,
    web_port: u16,
    adb_port: u16,
    tunnel_web_port: u16,
    tunnel_adb_port: u16,
    tunnel_connected: bool,
    stress_running: bool,
    soc_temp_c: Option<f32>,
    uptime: String,
    #[serde(default)]
    ssh_pub_key: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DeviceRecord {
    mac: String,
    serial: String,
    model: String,
    brand: String,
    android_version: String,
    sdk_int: i32,
    soc_platform: String,
    lan_ip: String,
    public_ip: String,
    app_version: String,
    web_port: u16,
    adb_port: u16,
    tunnel_web_port: u16,
    tunnel_adb_port: u16,
    tunnel_connected: bool,
    stress_running: bool,
    soc_temp_c: Option<f32>,
    uptime: String,
    last_seen_epoch: i64,
    #[serde(default)]
    alias: String,
    #[serde(default)]
    group: String,
    #[serde(default)]
    notes: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DeviceView {
    #[serde(flatten)]
    record: DeviceRecord,
    online: bool,
    ws_connected: bool,
    last_seen_ago_sec: i64,
    adb_tunnel_cmd: String,
}

#[derive(Debug, Deserialize)]
struct LoginRequest {
    #[serde(default)]
    username: String,
    password: String,
}

#[derive(Debug, Serialize)]
struct LoginResponse {
    token: String,
    username: String,
    role: Role,
    ssh_host: String,
    ssh_user: String,
}

#[derive(Debug, Deserialize)]
struct PatchDeviceRequest {
    #[serde(default)]
    alias: Option<String>,
    #[serde(default)]
    group: Option<String>,
    #[serde(default)]
    notes: Option<String>,
}

#[derive(Debug, Deserialize)]
struct CreateUserRequest {
    username: String,
    password: String,
    role: Role,
}

#[derive(Debug, Deserialize)]
struct UpdateUserRequest {
    #[serde(default)]
    password: Option<String>,
    #[serde(default)]
    role: Option<Role>,
}

#[derive(Debug, Deserialize)]
struct LimitQuery {
    #[serde(default)]
    limit: Option<usize>,
}

#[tokio::main]
async fn main() {
    let listen_addr = env::var("FLEET_LISTEN").unwrap_or_else(|_| "0.0.0.0:9090".to_string());
    let admin_password = env::var("FLEET_ADMIN_PASSWORD").unwrap_or_else(|_| "toolhub123".to_string());
    let device_token = env::var("FLEET_DEVICE_TOKEN").unwrap_or_else(|_| "".to_string());
    let ssh_host = env::var("FLEET_SSH_HOST").unwrap_or_else(|_| "127.0.0.1".to_string());
    let ssh_user = env::var("FLEET_SSH_USER").unwrap_or_else(|_| "toolhub-relay".to_string());
    let data_file = env::var("FLEET_DATA_FILE").unwrap_or_else(|_| "fleet_devices.json".to_string());
    let users_file = PathBuf::from(env::var("FLEET_USERS_FILE").unwrap_or_else(|_| "fleet_users.json".to_string()));
    let secret_file = PathBuf::from(env::var("FLEET_SECRET_FILE").unwrap_or_else(|_| ".fleet_secret".to_string()));
    let history_dir = PathBuf::from(env::var("FLEET_HISTORY_DIR").unwrap_or_else(|_| "fleet_history".to_string()));
    let audit_file = PathBuf::from(env::var("FLEET_AUDIT_FILE").unwrap_or_else(|_| "fleet_audit.jsonl".to_string()));

    let jwt_secret = match env::var("FLEET_JWT_SECRET") {
        Ok(s) if !s.trim().is_empty() => s.into_bytes(),
        _ => load_or_create_secret(&secret_file),
    };

    let initial_devices: HashMap<String, DeviceRecord> = fs::read_to_string(&data_file)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default();

    let initial_users = load_users(&users_file, &admin_password);

    let state = AppState {
        devices: Arc::new(RwLock::new(initial_devices)),
        users: Arc::new(RwLock::new(initial_users)),
        last_history_ts: Arc::new(RwLock::new(HashMap::new())),
        ws_tunnels: Arc::new(RwLock::new(HashMap::new())),
        pending_http: Arc::new(RwLock::new(HashMap::new())),
        pending_chunks: Arc::new(RwLock::new(HashMap::new())),
        http_client: Client::builder()
            .timeout(std::time::Duration::from_secs(30))
            .build()
            .expect("http client"),
        jwt_secret,
        device_token,
        ssh_host,
        ssh_user,
        data_file,
        users_file,
        history_dir,
        audit_file,
    };

    let web_dist = env::var("FLEET_WEB_DIST").unwrap_or_else(|_| {
        for candidate in ["apps/web/dist", "../web/dist", "frontend/dist"] {
            if PathBuf::from(candidate).join("index.html").exists() {
                return candidate.to_string();
            }
        }
        "apps/web/dist".to_string()
    });
    let index_html = PathBuf::from(&web_dist).join("index.html");
    let static_service = ServeDir::new(&web_dist).not_found_service(ServeFile::new(index_html));

    let scan_state = state.clone();
    tokio::spawn(async move {
        loop {
            let _ = scan_lan_devices(scan_state.clone()).await;
            tokio::time::sleep(std::time::Duration::from_secs(5)).await;
        }
    });

    if let Some(webhook_url) = env::var("FLEET_ALERT_WEBHOOK")
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
    {
        let temp_threshold = env::var("FLEET_ALERT_TEMP_C")
            .ok()
            .and_then(|v| v.parse::<f32>().ok())
            .unwrap_or(80.0);
        let alert_state = state.clone();
        tokio::spawn(async move {
            run_alert_monitor(alert_state, webhook_url, temp_threshold).await;
        });
    }

    let app = Router::new()
        .route("/api/health", get(|| async { Json(serde_json::json!({"ok": true})) }))
        .route("/api/auth/login", post(handle_login))
        .route("/api/auth/me", get(handle_me))
        .route("/api/users", get(handle_list_users).post(handle_create_user))
        .route("/api/users/{username}", patch(handle_update_user).delete(handle_delete_user))
        .route("/api/audit", get(handle_list_audit))
        .route("/api/devices/heartbeat", post(handle_heartbeat))
        .route("/api/devices/scan", post(handle_scan_devices))
        .route("/api/devices", get(handle_list_devices))
        .route(
            "/api/devices/{mac}",
            patch(handle_patch_device).delete(handle_delete_device),
        )
        .route("/api/devices/{mac}/history", get(handle_device_history))
        .route("/api/tunnel/ws", get(handle_ws_upgrade))
        .route("/proxy/{mac}", any(handle_proxy_root))
        .route("/proxy/{mac}/", any(handle_proxy_root))
        .route("/proxy/{mac}/{*path}", any(handle_proxy_path))
        .fallback_service(static_service)
        .layer(CorsLayer::permissive())
        .with_state(state);

    let listener = tokio::net::TcpListener::bind(&listen_addr)
        .await
        .expect("bind fleet server");
    println!("toolhub-fleet listening on http://{}", listen_addr);
    axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .await
    .expect("serve");
}

fn record_audit(
    state: &AppState,
    claims: &TokenClaims,
    action: &str,
    target: &str,
    detail: &str,
    ok: bool,
) {
    let entry = AuditEntry {
        ts: Utc::now().to_rfc3339(),
        actor: claims.sub.clone(),
        role: claims.role.as_str().to_string(),
        action: action.to_string(),
        target: target.to_string(),
        detail: detail.to_string(),
        ok,
    };
    append_audit_entry(&state.audit_file, &entry);
}

fn extract_raw_token(headers: &HeaderMap, query: Option<&str>) -> Option<String> {
    if let Some(q) = query {
        for pair in q.split('&') {
            if let Some(val) = pair.strip_prefix("token=") {
                let t = val.trim();
                if !t.is_empty() {
                    return Some(t.to_string());
                }
            }
        }
    }
    if let Some(auth) = headers.get(header::AUTHORIZATION).and_then(|v| v.to_str().ok()) {
        if let Some(token) = auth.strip_prefix("Bearer ") {
            let t = token.trim();
            if !t.is_empty() {
                return Some(t.to_string());
            }
        }
    }
    if let Some(cookie_hdr) = headers.get(header::COOKIE).and_then(|v| v.to_str().ok()) {
        for part in cookie_hdr.split(';') {
            if let Some(val) = part.trim().strip_prefix("fleet_session=") {
                let t = val.trim();
                if !t.is_empty() {
                    return Some(t.to_string());
                }
            }
        }
    }
    None
}

async fn authenticate(
    state: &AppState,
    headers: &HeaderMap,
    query: Option<&str>,
) -> Option<(String, TokenClaims)> {
    let raw = extract_raw_token(headers, query)?;
    let claims = verify_token(&state.jwt_secret, &raw)?;
    let users = state.users.read().await;
    let user = users.get(&claims.sub)?;
    Some((
        raw,
        TokenClaims {
            sub: user.username.clone(),
            role: user.role,
            exp: claims.exp,
        },
    ))
}

async fn handle_login(
    State(state): State<AppState>,
    Json(req): Json<LoginRequest>,
) -> Response {
    let username = if req.username.trim().is_empty() {
        "admin".to_string()
    } else {
        req.username.trim().to_string()
    };

    let user = {
        let users = state.users.read().await;
        users.get(&username).cloned()
    };

    let Some(user) = user else {
        return (
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({"error": "Invalid username or password"})),
        )
            .into_response();
    };

    if !verify_password(&user, &req.password) {
        return (
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({"error": "Invalid username or password"})),
        )
            .into_response();
    }

    let token = sign_token(&state.jwt_secret, &user.username, user.role);
    let claims = TokenClaims {
        sub: user.username.clone(),
        role: user.role,
        exp: 0,
    };
    record_audit(&state, &claims, "auth.login", &user.username, "login", true);

    let mut resp = Json(LoginResponse {
        token: token.clone(),
        username: user.username,
        role: user.role,
        ssh_host: state.ssh_host.clone(),
        ssh_user: state.ssh_user.clone(),
    })
    .into_response();
    let cookie = format!("fleet_session={}; Path=/; HttpOnly; SameSite=Lax", token);
    if let Ok(val) = cookie.parse() {
        resp.headers_mut().insert(header::SET_COOKIE, val);
    }
    resp
}

async fn handle_me(State(state): State<AppState>, headers: HeaderMap) -> Response {
    let Some((_, claims)) = authenticate(&state, &headers, None).await else {
        return (StatusCode::UNAUTHORIZED, Json(serde_json::json!({"error": "Unauthorized"}))).into_response();
    };
    Json(serde_json::json!({
        "username": claims.sub,
        "role": claims.role,
    }))
    .into_response()
}

async fn handle_list_users(State(state): State<AppState>, headers: HeaderMap) -> Response {
    let Some((_, claims)) = authenticate(&state, &headers, None).await else {
        return (StatusCode::UNAUTHORIZED, Json(serde_json::json!({"error": "Unauthorized"}))).into_response();
    };
    if !claims.role.is_admin() {
        return (StatusCode::FORBIDDEN, Json(serde_json::json!({"error": "Admin role required"}))).into_response();
    }
    let users = state.users.read().await;
    let mut list: Vec<UserView> = users
        .values()
        .map(|u| UserView {
            username: u.username.clone(),
            role: u.role,
            created_at: u.created_at.clone(),
        })
        .collect();
    list.sort_by(|a, b| a.username.cmp(&b.username));
    Json(list).into_response()
}

async fn handle_create_user(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<CreateUserRequest>,
) -> Response {
    let Some((_, claims)) = authenticate(&state, &headers, None).await else {
        return (StatusCode::UNAUTHORIZED, Json(serde_json::json!({"error": "Unauthorized"}))).into_response();
    };
    if !claims.role.is_admin() {
        return (StatusCode::FORBIDDEN, Json(serde_json::json!({"error": "Admin role required"}))).into_response();
    }
    let username = req.username.trim().to_string();
    if username.len() < 2
        || !username
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
    {
        return (StatusCode::BAD_REQUEST, Json(serde_json::json!({"error": "Invalid username"}))).into_response();
    }
    if req.password.len() < 4 {
        return (StatusCode::BAD_REQUEST, Json(serde_json::json!({"error": "Password too short (min 4)"}))).into_response();
    }

    let mut users = state.users.write().await;
    if users.contains_key(&username) {
        return (StatusCode::CONFLICT, Json(serde_json::json!({"error": "User already exists"}))).into_response();
    }
    let rec = make_user(&username, &req.password, req.role);
    users.insert(username.clone(), rec);
    save_users(&state.users_file, &users);
    record_audit(
        &state,
        &claims,
        "user.create",
        &username,
        req.role.as_str(),
        true,
    );
    Json(serde_json::json!({"ok": true, "username": username, "role": req.role})).into_response()
}

async fn handle_update_user(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(username): Path<String>,
    Json(req): Json<UpdateUserRequest>,
) -> Response {
    let Some((_, claims)) = authenticate(&state, &headers, None).await else {
        return (StatusCode::UNAUTHORIZED, Json(serde_json::json!({"error": "Unauthorized"}))).into_response();
    };
    if !claims.role.is_admin() {
        return (StatusCode::FORBIDDEN, Json(serde_json::json!({"error": "Admin role required"}))).into_response();
    }
    let target = username.trim().to_string();
    let mut users = state.users.write().await;
    let admin_count = users.values().filter(|u| u.role == Role::Admin).count();
    let Some(existing) = users.get(&target).cloned() else {
        return (StatusCode::NOT_FOUND, Json(serde_json::json!({"error": "User not found"}))).into_response();
    };

    let mut updated = existing.clone();
    if let Some(next_role) = req.role {
        if existing.role == Role::Admin && next_role != Role::Admin && admin_count <= 1 {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({"error": "Cannot demote the last admin"})),
            )
                .into_response();
        }
        updated.role = next_role;
    }
    if let Some(pass) = req.password {
        if !pass.is_empty() {
            if pass.len() < 4 {
                return (
                    StatusCode::BAD_REQUEST,
                    Json(serde_json::json!({"error": "Password too short (min 4)"})),
                )
                    .into_response();
            }
            let fresh = make_user(&updated.username, &pass, updated.role);
            updated.salt = fresh.salt;
            updated.password_hash = fresh.password_hash;
        }
    }
    users.insert(target.clone(), updated.clone());
    save_users(&state.users_file, &users);
    record_audit(
        &state,
        &claims,
        "user.update",
        &target,
        updated.role.as_str(),
        true,
    );
    Json(serde_json::json!({"ok": true})).into_response()
}

async fn handle_delete_user(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(username): Path<String>,
) -> Response {
    let Some((_, claims)) = authenticate(&state, &headers, None).await else {
        return (StatusCode::UNAUTHORIZED, Json(serde_json::json!({"error": "Unauthorized"}))).into_response();
    };
    if !claims.role.is_admin() {
        return (StatusCode::FORBIDDEN, Json(serde_json::json!({"error": "Admin role required"}))).into_response();
    }
    let target = username.trim().to_string();
    if target == claims.sub {
        return (StatusCode::BAD_REQUEST, Json(serde_json::json!({"error": "Cannot delete current user"}))).into_response();
    }
    let mut users = state.users.write().await;
    let admin_count = users.values().filter(|u| u.role == Role::Admin).count();
    if users.get(&target).map(|u| u.role == Role::Admin).unwrap_or(false) && admin_count <= 1 {
        return (StatusCode::BAD_REQUEST, Json(serde_json::json!({"error": "Cannot delete the last admin"}))).into_response();
    }
    users.remove(&target);
    save_users(&state.users_file, &users);
    record_audit(&state, &claims, "user.delete", &target, "deleted", true);
    Json(serde_json::json!({"ok": true})).into_response()
}

async fn handle_list_audit(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(q): Query<LimitQuery>,
) -> Response {
    let Some((_, _claims)) = authenticate(&state, &headers, None).await else {
        return (StatusCode::UNAUTHORIZED, Json(serde_json::json!({"error": "Unauthorized"}))).into_response();
    };
    let limit = q.limit.unwrap_or(150).min(500);
    Json(read_audit_entries(&state.audit_file, limit)).into_response()
}

async fn upsert_device(state: &AppState, payload: HeartbeatPayload, public_ip: String) -> String {
    let mac = payload.mac.trim().to_uppercase();
    let lan_ip = if payload.lan_ip.is_empty() || payload.lan_ip == "-" {
        public_ip.clone()
    } else {
        payload.lan_ip
    };
    let now = Utc::now().timestamp();

    if let Some(pub_key) = payload.ssh_pub_key.as_deref() {
        let _ = ensure_ssh_authorized_key(&state.ssh_user, pub_key);
    }

    let snapshot = {
        let mut map = state.devices.write().await;
        let prev = map.get(&mac).cloned();
        let rec = DeviceRecord {
            mac: mac.clone(),
            serial: payload.serial,
            model: payload.model,
            brand: payload.brand,
            android_version: payload.android_version,
            sdk_int: payload.sdk_int,
            soc_platform: payload.soc_platform,
            lan_ip,
            public_ip,
            app_version: payload.app_version,
            web_port: payload.web_port,
            adb_port: payload.adb_port,
            tunnel_web_port: payload.tunnel_web_port,
            tunnel_adb_port: payload.tunnel_adb_port,
            tunnel_connected: payload.tunnel_connected,
            stress_running: payload.stress_running,
            soc_temp_c: payload.soc_temp_c,
            uptime: payload.uptime,
            last_seen_epoch: now,
            alias: prev.as_ref().map(|p| p.alias.clone()).unwrap_or_default(),
            group: prev.as_ref().map(|p| p.group.clone()).unwrap_or_default(),
            notes: prev.as_ref().map(|p| p.notes.clone()).unwrap_or_default(),
        };
        map.insert(mac.clone(), rec);
        serde_json::to_string_pretty(&*map).ok()
    };
    if let Some(json_str) = snapshot {
        let _ = tokio::fs::write(&state.data_file, json_str).await;
    }

    // Persist telemetry history point at most every 30s or immediately on stress state change
    let should_record = {
        let mut last_map = state.last_history_ts.write().await;
        let prev = last_map.get(&mac).copied();
        let record_now = match prev {
            None => true,
            Some((last_ts, last_stress)) => now - last_ts >= 30 || last_stress != payload.stress_running,
        };
        if record_now {
            last_map.insert(mac.clone(), (now, payload.stress_running));
        }
        record_now
    };
    if should_record {
        append_telemetry_point(
            &state.history_dir,
            &mac,
            &TelemetryPoint {
                ts_epoch: now,
                soc_temp_c: payload.soc_temp_c.map(|v| v as f64),
                stress_running: payload.stress_running,
                online: true,
            },
        );
    }

    mac
}

async fn handle_ws_upgrade(
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
) -> Response {
    if !state.device_token.is_empty() {
        let token = headers
            .get("X-Device-Token")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        if token != state.device_token {
            return (StatusCode::UNAUTHORIZED, "Bad device token").into_response();
        }
    }

    let public_ip = headers
        .get("X-Forwarded-For")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.split(',').next())
        .map(|s| s.trim().to_string())
        .unwrap_or_else(|| addr.ip().to_string());

    ws.on_upgrade(move |socket| run_ws_tunnel(socket, state, public_ip))
}

async fn run_ws_tunnel(socket: WebSocket, state: AppState, public_ip: String) {
    let (mut ws_tx, mut ws_rx) = socket.split();
    let (tx, mut rx) = mpsc::channel::<String>(64);
    let conn_id = Uuid::new_v4().to_string();

    let writer_task = tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            if ws_tx.send(Message::Text(msg.into())).await.is_err() {
                break;
            }
        }
    });

    let mut bound_mac: Option<String> = None;

    while let Some(Ok(frame)) = ws_rx.next().await {
        match frame {
            Message::Text(text) => {
                if let Ok(parsed) = serde_json::from_str::<WsClientMsg>(&text) {
                    match parsed {
                        WsClientMsg::Hello { heartbeat } | WsClientMsg::Heartbeat { heartbeat } => {
                            let mac = upsert_device(&state, heartbeat, public_ip.clone()).await;
                            if bound_mac.as_deref() != Some(mac.as_str()) {
                                bound_mac = Some(mac.clone());
                                state.ws_tunnels.write().await.insert(
                                    mac,
                                    WsTunnelEntry {
                                        conn_id: conn_id.clone(),
                                        tx: tx.clone(),
                                    },
                                );
                            }
                        }
                        WsClientMsg::HttpResp(resp) => {
                            if let Some(waiter) = state.pending_http.write().await.remove(&resp.id) {
                                let _ = waiter.send(resp);
                            }
                        }
                        WsClientMsg::HttpRespChunk(chunk) => {
                            let decoded = B64.decode(chunk.body_b64.as_bytes()).unwrap_or_default();
                            let completed: Option<WsHttpResp> = {
                                let mut acc_map = state.pending_chunks.write().await;
                                let acc = acc_map.entry(chunk.id.clone()).or_insert_with(|| {
                                    ChunkAccumulator {
                                        status: if chunk.status > 0 { chunk.status } else { 200 },
                                        headers: chunk.headers.clone(),
                                        bytes: Vec::new(),
                                    }
                                });
                                if chunk.status > 0 {
                                    acc.status = chunk.status;
                                }
                                if !chunk.headers.is_empty() {
                                    acc.headers.extend(chunk.headers);
                                }
                                acc.bytes.extend_from_slice(&decoded);
                                if chunk.eof {
                                    acc_map.remove(&chunk.id).map(|done| WsHttpResp {
                                        id: chunk.id.clone(),
                                        status: done.status,
                                        headers: done.headers,
                                        body_b64: B64.encode(&done.bytes),
                                    })
                                } else {
                                    None
                                }
                            };
                            if let Some(resp) = completed {
                                if let Some(waiter) = state.pending_http.write().await.remove(&resp.id) {
                                    let _ = waiter.send(resp);
                                }
                            }
                        }
                    }
                }
            }
            Message::Close(_) => break,
            _ => {}
        }
    }

    if let Some(mac) = bound_mac {
        let mut tunnels = state.ws_tunnels.write().await;
        if tunnels.get(&mac).map(|e| e.conn_id == conn_id).unwrap_or(false) {
            tunnels.remove(&mac);
        }
    }
    writer_task.abort();
}

async fn handle_heartbeat(
    State(state): State<AppState>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(payload): Json<HeartbeatPayload>,
) -> Response {
    if !state.device_token.is_empty() {
        let token = headers
            .get("X-Device-Token")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        if token != state.device_token {
            return (StatusCode::UNAUTHORIZED, Json(serde_json::json!({"error": "Bad device token"}))).into_response();
        }
    }

    let public_ip = headers
        .get("X-Forwarded-For")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.split(',').next())
        .map(|s| s.trim().to_string())
        .unwrap_or_else(|| addr.ip().to_string());

    let mac = upsert_device(&state, payload, public_ip).await;
    Json(serde_json::json!({"ok": true, "mac": mac})).into_response()
}

async fn handle_scan_devices(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Response {
    let Some((_, claims)) = authenticate(&state, &headers, None).await else {
        return (StatusCode::UNAUTHORIZED, Json(serde_json::json!({"error": "Unauthorized"}))).into_response();
    };
    if !claims.role.can_operate() {
        return (StatusCode::FORBIDDEN, Json(serde_json::json!({"error": "Read-only viewer cannot scan"}))).into_response();
    }
    let found = scan_lan_devices(state).await.unwrap_or(0);
    Json(serde_json::json!({"ok": true, "discovered": found})).into_response()
}

async fn scan_lan_devices(state: AppState) -> std::io::Result<usize> {
    let socket = tokio::net::UdpSocket::bind("0.0.0.0:0").await?;
    socket.set_broadcast(true)?;
    let _ = socket.send_to(b"TOOLHUB_DISCOVER", "255.255.255.255:18088").await;

    let mut buf = [0u8; 4096];
    let mut count = 0usize;
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_millis(800);

    loop {
        let now = tokio::time::Instant::now();
        if now >= deadline {
            break;
        }
        let rem = deadline - now;
        match tokio::time::timeout(rem, socket.recv_from(&mut buf)).await {
            Ok(Ok((n, src))) => {
                if let Ok(mut payload) = serde_json::from_slice::<HeartbeatPayload>(&buf[..n]) {
                    if payload.lan_ip.is_empty() || payload.lan_ip == "-" {
                        payload.lan_ip = src.ip().to_string();
                    }
                    upsert_device(&state, payload, src.ip().to_string()).await;
                    count += 1;
                }
            }
            _ => break,
        }
    }
    Ok(count)
}

async fn handle_list_devices(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Response {
    if authenticate(&state, &headers, None).await.is_none() {
        return (StatusCode::UNAUTHORIZED, Json(serde_json::json!({"error": "Unauthorized"}))).into_response();
    }
    let now = Utc::now().timestamp();
    let map = state.devices.read().await;
    let ws_tunnels = state.ws_tunnels.read().await;
    let mut list: Vec<DeviceView> = map
        .values()
        .cloned()
        .map(|rec| {
            let ago = (now - rec.last_seen_epoch).max(0);
            let ws_connected = ws_tunnels.contains_key(&rec.mac);
            let online = ws_connected || ago <= 45;
            let adb_cmd = format!(
                "ssh -N -L {}:127.0.0.1:{} {}@{} && adb connect 127.0.0.1:{}",
                rec.adb_port, rec.tunnel_adb_port, state.ssh_user, state.ssh_host, rec.adb_port
            );
            DeviceView {
                record: rec,
                online,
                ws_connected,
                last_seen_ago_sec: ago,
                adb_tunnel_cmd: adb_cmd,
            }
        })
        .collect();
    list.sort_by(|a, b| b.online.cmp(&a.online).then_with(|| a.record.mac.cmp(&b.record.mac)));
    Json(list).into_response()
}

async fn handle_patch_device(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(mac): Path<String>,
    Json(req): Json<PatchDeviceRequest>,
) -> Response {
    let Some((_, claims)) = authenticate(&state, &headers, None).await else {
        return (StatusCode::UNAUTHORIZED, Json(serde_json::json!({"error": "Unauthorized"}))).into_response();
    };
    if !claims.role.can_operate() {
        return (StatusCode::FORBIDDEN, Json(serde_json::json!({"error": "Viewer role is read-only"}))).into_response();
    }
    let key = mac.trim().to_uppercase();
    let snapshot = {
        let mut map = state.devices.write().await;
        let Some(rec) = map.get_mut(&key) else {
            return (StatusCode::NOT_FOUND, Json(serde_json::json!({"error": "Device not found"}))).into_response();
        };
        if let Some(a) = req.alias {
            rec.alias = a.trim().to_string();
        }
        if let Some(g) = req.group {
            rec.group = g.trim().to_string();
        }
        if let Some(n) = req.notes {
            rec.notes = n.trim().to_string();
        }
        serde_json::to_string_pretty(&*map).ok()
    };
    if let Some(json_str) = snapshot {
        let _ = tokio::fs::write(&state.data_file, json_str).await;
    }
    record_audit(&state, &claims, "device.patch", &key, "updated metadata", true);
    Json(serde_json::json!({"ok": true})).into_response()
}

async fn handle_device_history(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(mac): Path<String>,
    Query(q): Query<LimitQuery>,
) -> Response {
    if authenticate(&state, &headers, None).await.is_none() {
        return (StatusCode::UNAUTHORIZED, Json(serde_json::json!({"error": "Unauthorized"}))).into_response();
    }
    let key = mac.trim().to_uppercase();
    let limit = q.limit.unwrap_or(240).min(2880);
    Json(read_telemetry_history(&state.history_dir, &key, limit)).into_response()
}

async fn handle_delete_device(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(mac): Path<String>,
) -> Response {
    let Some((_, claims)) = authenticate(&state, &headers, None).await else {
        return (StatusCode::UNAUTHORIZED, Json(serde_json::json!({"error": "Unauthorized"}))).into_response();
    };
    if !claims.role.can_operate() {
        return (StatusCode::FORBIDDEN, Json(serde_json::json!({"error": "Viewer role is read-only"}))).into_response();
    }
    let key = mac.trim().to_uppercase();
    let snapshot = {
        let mut map = state.devices.write().await;
        map.remove(&key);
        serde_json::to_string_pretty(&*map).ok()
    };
    if let Some(json_str) = snapshot {
        let _ = tokio::fs::write(&state.data_file, json_str).await;
    }
    record_audit(&state, &claims, "device.delete", &key, "removed", true);
    Json(serde_json::json!({"ok": true})).into_response()
}

async fn handle_proxy_root(
    State(state): State<AppState>,
    Path(mac): Path<String>,
    req: Request,
) -> Response {
    proxy_to_device(state, mac, String::new(), req).await
}

async fn handle_proxy_path(
    State(state): State<AppState>,
    Path((mac, path)): Path<(String, String)>,
    req: Request,
) -> Response {
    proxy_to_device(state, mac, path, req).await
}

async fn proxy_to_device(
    state: AppState,
    mac: String,
    path: String,
    req: Request,
) -> Response {
    let query_str = req.uri().query().map(|q| q.to_string());
    let auth_res = authenticate(&state, req.headers(), query_str.as_deref()).await;
    let Some((session_token, claims)) = auth_res else {
        return (StatusCode::UNAUTHORIZED, "Unauthorized: please log in at /").into_response();
    };

    let method = req.method().clone();
    let is_mutation = matches!(method, Method::POST | Method::PUT | Method::PATCH | Method::DELETE);
    if is_mutation && !claims.role.can_operate() {
        return (
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({"ok": false, "error": "Viewer role is read-only"})),
        )
            .into_response();
    }

    let key = mac.trim().to_uppercase();
    let ws_entry = state.ws_tunnels.read().await.get(&key).cloned();

    let mut fwd_headers = HashMap::new();
    if let Some(pin) = req.headers().get("X-ToolHub-Pin").and_then(|v| v.to_str().ok()) {
        fwd_headers.insert("X-ToolHub-Pin".to_string(), pin.to_string());
    }
    if let Some(ct) = req.headers().get(header::CONTENT_TYPE).and_then(|v| v.to_str().ok()) {
        fwd_headers.insert("Content-Type".to_string(), ct.to_string());
    }

    let body_bytes = if method == Method::POST || method == Method::PUT || method == Method::PATCH {
        match axum::body::to_bytes(req.into_body(), 10 * 1024 * 1024).await {
            Ok(b) => b.to_vec(),
            Err(_) => return (StatusCode::BAD_REQUEST, "Body too large").into_response(),
        }
    } else {
        Vec::new()
    };

    if is_mutation {
        let body_preview = String::from_utf8_lossy(&body_bytes);
        let snippet: String = body_preview.chars().take(120).collect();
        record_audit(
            &state,
            &claims,
            &format!("proxy.{}", method.as_str()),
            &key,
            &format!("/{path} {snippet}"),
            true,
        );
    }

    let clean_query = query_str
        .as_deref()
        .map(|q| {
            let kept: Vec<&str> = q
                .split('&')
                .filter(|p| !p.starts_with("token=") && !p.is_empty())
                .collect();
            if kept.is_empty() {
                String::new()
            } else {
                format!("?{}", kept.join("&"))
            }
        })
        .unwrap_or_default();

    let rel_path = if path.is_empty() {
        format!("/{}", clean_query)
    } else {
        format!("/{}{}", path, clean_query)
    };

    let mut out_resp = if let Some(ws) = ws_entry {
        let req_id = Uuid::new_v4().to_string();
        let (resp_tx, resp_rx) = oneshot::channel();
        state.pending_http.write().await.insert(req_id.clone(), resp_tx);

        let ws_msg = WsServerMsg::HttpReq {
            id: req_id.clone(),
            method: method.to_string(),
            path: rel_path,
            headers: fwd_headers,
            body_b64: if body_bytes.is_empty() {
                String::new()
            } else {
                B64.encode(&body_bytes)
            },
        };
        let msg_json = serde_json::to_string(&ws_msg).unwrap_or_default();
        if ws.tx.send(msg_json).await.is_err() {
            state.pending_http.write().await.remove(&req_id);
            return (StatusCode::BAD_GATEWAY, "WS tunnel send failed").into_response();
        }

        match tokio::time::timeout(std::time::Duration::from_secs(60), resp_rx).await {
            Ok(Ok(ws_resp)) => {
                let status = StatusCode::from_u16(ws_resp.status).unwrap_or(StatusCode::BAD_GATEWAY);
                let decoded = B64.decode(ws_resp.body_b64.as_bytes()).unwrap_or_default();
                let mut builder = Response::builder().status(status);
                if let Some(ct) = ws_resp
                    .headers
                    .get("content-type")
                    .or_else(|| ws_resp.headers.get("Content-Type"))
                {
                    builder = builder.header(header::CONTENT_TYPE, ct.as_str());
                }
                if let Some(cd) = ws_resp
                    .headers
                    .get("content-disposition")
                    .or_else(|| ws_resp.headers.get("Content-Disposition"))
                {
                    builder = builder.header(header::CONTENT_DISPOSITION, cd.as_str());
                }
                builder.body(Body::from(decoded)).unwrap_or_else(|_| StatusCode::BAD_GATEWAY.into_response())
            }
            _ => {
                state.pending_http.write().await.remove(&req_id);
                state.pending_chunks.write().await.remove(&req_id);
                (StatusCode::GATEWAY_TIMEOUT, "WS tunnel request timeout").into_response()
            }
        }
    } else {
        let target_base = {
            let map = state.devices.read().await;
            let Some(dev) = map.get(&key) else {
                return (StatusCode::NOT_FOUND, "Device not registered").into_response();
            };
            if dev.tunnel_connected && dev.tunnel_web_port > 0 {
                format!("http://127.0.0.1:{}", dev.tunnel_web_port)
            } else {
                format!("http://{}:{}", dev.lan_ip, dev.web_port)
            }
        };

        let target_url = format!("{}{}", target_base, rel_path);
        let reqwest_method = reqwest::Method::from_bytes(method.as_str().as_bytes())
            .unwrap_or(reqwest::Method::GET);

        let mut builder = state.http_client.request(reqwest_method, &target_url);
        for (k, v) in fwd_headers {
            builder = builder.header(&k, v);
        }
        if !body_bytes.is_empty() {
            builder = builder.body(body_bytes);
        }

        let Ok(upstream) = builder.send().await else {
            return (
                StatusCode::BAD_GATEWAY,
                format!("Failed to reach device at {}", target_base),
            )
                .into_response();
        };

        let status = StatusCode::from_u16(upstream.status().as_u16())
            .unwrap_or(StatusCode::BAD_GATEWAY);
        let mut resp_builder = Response::builder().status(status);
        if let Some(ct) = upstream.headers().get(header::CONTENT_TYPE) {
            resp_builder = resp_builder.header(header::CONTENT_TYPE, ct);
        }
        if let Some(cd) = upstream.headers().get(header::CONTENT_DISPOSITION) {
            resp_builder = resp_builder.header(header::CONTENT_DISPOSITION, cd);
        }
        let body = Body::from_stream(upstream.bytes_stream());
        resp_builder.body(body).unwrap_or_else(|_| StatusCode::BAD_GATEWAY.into_response())
    };

    if query_str.as_deref().map(|q| q.contains("token=")).unwrap_or(false) {
        let cookie = format!("fleet_session={}; Path=/; HttpOnly; SameSite=Lax", session_token);
        if let Ok(val) = cookie.parse() {
            out_resp.headers_mut().insert(header::SET_COOKIE, val);
        }
    }
    out_resp
}

async fn run_alert_monitor(state: AppState, webhook_url: String, temp_threshold_c: f32) {
    let mut was_online: HashMap<String, bool> = HashMap::new();
    let mut last_alert_epoch: HashMap<String, i64> = HashMap::new();
    const COOLDOWN_SEC: i64 = 900;

    loop {
        tokio::time::sleep(std::time::Duration::from_secs(30)).await;
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);

        let devices: Vec<DeviceRecord> = state.devices.read().await.values().cloned().collect();
        let ws_keys: std::collections::HashSet<String> = {
            state.ws_tunnels.read().await.keys().cloned().collect()
        };

        let mut alerts: Vec<String> = Vec::new();
        for dev in devices {
            let ws_up = ws_keys.contains(&dev.mac);
            let online = ws_up || (now - dev.last_seen_epoch <= 30);
            let label = if dev.alias.trim().is_empty() {
                format!("{} ({})", dev.model, dev.mac)
            } else {
                format!("{} [{}]", dev.alias, dev.mac)
            };

            if was_online.get(&dev.mac).copied() == Some(true) && !online {
                let key = format!("offline:{}", dev.mac);
                let prev = last_alert_epoch.get(&key).copied().unwrap_or(0);
                if now - prev >= COOLDOWN_SEC {
                    last_alert_epoch.insert(key, now);
                    alerts.push(format!("[ToolHub Alert] Device OFFLINE: {}", label));
                }
            }
            was_online.insert(dev.mac.clone(), online);

            if online {
                if let Some(temp) = dev.soc_temp_c {
                    if temp >= temp_threshold_c {
                        let key = format!("overheat:{}", dev.mac);
                        let prev = last_alert_epoch.get(&key).copied().unwrap_or(0);
                        if now - prev >= COOLDOWN_SEC {
                            last_alert_epoch.insert(key, now);
                            alerts.push(format!(
                                "[ToolHub Alert] High SoC Temp: {} reached {:.1}°C (threshold {:.1}°C)",
                                label, temp, temp_threshold_c
                            ));
                        }
                    }
                }
            }
        }

        for msg in alerts {
            let body = serde_json::json!({
                "msgtype": "text",
                "text": { "content": msg }
            });
            let _ = state
                .http_client
                .post(&webhook_url)
                .header("Content-Type", "application/json")
                .body(body.to_string())
                .send()
                .await;
        }
    }
}
